package com.android.gudao.iphoneos.engine

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 宿主算力池的 CPU 亲和性包装探测（0.26.0）。
 *
 * 目标是把「宿主给 QEMU 多少物理核」和「XNU 看到几个 vCPU」分开：用 `taskset -c 0-N`
 * 把 QEMU 进程族允许调度到 N 个核， Guest vCPU 仍由 `-smp` 单独决定。
 *
 * 但包装有一个必须防住的失败模式：如果 `taskset` 不存在，或 `sched_setaffinity` 被
 * SELinux / cpuset 拒绝，它会打印错误后**直接退出**，QEMU 根本不会被 exec —— 结果是
 * 整个启动失败。所以这里绝不直接把前缀塞进命令行，而是先跑一次自检命令，只有退出码 0
 * 才返回该包装命令；否则返回 null，调用方走零回归的降级路径（QEMU 原样启动，
 * `tb-size` 那层算力预算仍 100% 生效）。
 *
 * 这里是纯 Kotlin（不引用 android.*），因此候选命令、自检、降级逻辑都能在 JVM 单测里
 * 用注入的 [execute] 完整断言；真机执行由 [defaultExecute] 负责。
 */
object QemuAffinity {

    data class Result(
        /** 可用的包装命令；null 表示降级，不要加任何前缀。 */
        val command: List<String>?,
        /** 一行给人看的说明，必须写进 AppLog / perf-stats，不允许静默降级。 */
        val note: String,
        /** 实际要用的掩码（`0-3,6,8`）；降级时为空串。启动前缀必须用它，不能另算。 */
        val mask: String = "",
        /**
         * 0.29.9：**完整**启动前缀（含 taskset 语法）。util-linux 用 `-c 6-7`，
         * Android 自带的 toybox 只认十六进制掩码 `c0`，两者不能互换。
         */
        val prefix: List<String> = emptyList(),
    )

    /**
     * 候选包装命令，按优先级排列。前两个是 Android 常见形态；`taskset` 裸命令走 PATH，
     * 云端 Linux 与部分 ROM 都有。toybox/busybox 需要子命令形式 `toybox taskset`。
     */
    val CANDIDATES: List<List<String>> = listOf(
        listOf("/system/bin/taskset"),
        listOf("/system/bin/toybox", "taskset"),
        listOf("/system/bin/busybox", "taskset"),
        listOf("taskset"),
    )

    /** 自检用的 shell：真机是 /system/bin/sh，云端 Linux 是 /bin/sh。 */
    val SHELL_CANDIDATES: List<String> = listOf("/system/bin/sh", "/bin/sh")

    fun detectShell(): String? = SHELL_CANDIDATES.firstOrNull { File(it).canExecute() }

    /**
     * 默认自检执行器：把命令跑起来，限时 2 秒，只有正常退出 0 才算可用。
     * stdout/stderr 重定向丢弃，避免管道没人读导致阻塞。
     */
    fun defaultExecute(argv: List<String>): Boolean = defaultExecuteCapture(argv) != null

    /**
     * 0.29.9：执行并**取回 stdout**。亲和性不能靠退出码判断——toybox 的 taskset 遇到
     * 不认识的 `-c` 会报错退出，但有些 ROM 的 setaffinity 失败后仍返回 0。只有让子进程
     * 自己打印 `Cpus_allowed_list` 才能确认真的钉住了。
     */
    fun defaultExecuteCapture(argv: List<String>): String? = runCatching {
        // 不能用 ProcessBuilder.Redirect.DISCARD / redirectError(Redirect)：那是 Java 9 API，
        // Android 的 java.lang.ProcessBuilder 只有 redirectOutput(File) / redirectError(File)。
        // /dev/null 在 Android（Linux 内核）上恒存在，且不会因管道无人读而阻塞。
        val devNull = File("/dev/null")
        val p = ProcessBuilder(argv)
            .redirectError(devNull)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return@runCatching null
        }
        if (p.exitValue() != 0) null else out
    }.getOrNull()

    /**
     * 探测一个真正可用的亲和性包装命令。
     *
     * @param hostCores 宿主算力池核数，决定自检与实际启动用的掩码（两者必须一致）。
     * @param shell 自检用的 shell；传 null 表示设备上找不到，直接降级。
     * @param execute 自检执行器，JVM 单测可注入，避免真的 fork 进程。
     */
    /** 十六进制掩码：toybox / busybox 的 taskset 只接受这种写法。 */
    fun hexMask(cores: List<Int>): String {
        var m = 0L
        for (c in cores) if (c in 0..63) m = m or (1L shl c)
        return java.lang.Long.toHexString(m)
    }

    /**
     * 0.29.9：**双语法**探测。Android 的 `/system/bin/taskset` 是 toybox 的，`-c` 直接
     * 「Unknown option」；只有桌面 Linux 上的 util-linux 版才认 `-c 6-7`。0.26.0 只按
     * util-linux 探测，于是在绝大多数手机上第一次就失败、整体降级——看起来像「设备不支持
     * 亲和性」，其实只是语法不对。
     *
     * 而且**不能只看退出码**：真正的判据是子进程自己打印的 `Cpus_allowed_list`。
     */
    fun resolve(
        hostCores: Int,
        shell: String? = detectShell(),
        topology: HostCpus.Topology = HostCpus.read(),
        execute: (List<String>) -> String? = ::defaultExecuteCapture,
    ): Result {
        val pool = QemuTopology.normalizeHostCores(hostCores)
        val tb = QemuTopology.tbSize(pool)
        if (!topology.known) {
            return Result(null, "读不到宿主拓扑（/proc/self/status 无 Cpus_allowed_list），不做钉核；TB 缓存仍按 $tb MiB 分配")
        }
        val chosen = HostCpus.chooseCores(topology.allowed, topology.ranked, pool)
        if (chosen.size >= topology.allowed.size) {
            return Result(
                null,
                "系统已放行全部 ${topology.allowed.size} 核（来源 ${topology.source}），无需钉核；" +
                    "TB 缓存按 $tb MiB 分配，QEMU 继承系统 cpuset",
            )
        }
        val mask = HostCpus.formatCpuList(chosen)
        val hex = hexMask(chosen)
        if (shell == null) {
            return Result(null, "找不到可用 shell，CPU 亲和性包装降级（仅 TB 缓存按 $tb MiB 生效）")
        }
        val bigNote = if (topology.big.isNotEmpty()) "，大核 ${HostCpus.formatCpuList(topology.big)}" else ""
        for (candidate in CANDIDATES) {
            // 两条语法都试：util-linux（-c 列表）、toybox/busybox（十六进制掩码）。
            val attempts = listOf(
                candidate + listOf("-c", mask),
                candidate + listOf(hex),
            )
            for (prefix in attempts) {
                val probe = prefix + listOf(shell, "-c", "grep Cpus_allowed_list /proc/self/status")
                val out = execute(probe) ?: continue
                val got = HostCpus.parseAllowed(out)
                if (got.isEmpty()) continue
                // 判据：子进程的允许集合必须**落在**我们选的核里。只判「包含」会被
                // 「退出码 0 但根本没钉」骗过去——那种情况下集合还是 0-7。
                if (got.all { it in chosen }) {
                    // 十六进制写法只多一个参数；`-c` 写法多两个（-c 和掩码）。
                    val style = if (prefix.size == candidate.size + 1) "十六进制掩码" else "-c 列表"
                    return Result(
                        candidate,
                        "CPU 亲和性已生效：${prefix.joinToString(" ")}（$style；" +
                            "系统放行 ${HostCpus.formatCpuList(topology.allowed)}$bigNote；TB 缓存 $tb MiB）",
                        mask,
                        prefix,
                    )
                }
            }
        }
        return Result(
            null,
            "taskset 两种语法都试过仍未钉住（可能被 cpuset 拒绝），降级为不钉核；" +
                "TB 缓存仍按 $tb MiB 分配",
        )
    }

    /** 0.29.5：给 UI / 日志用的一行拓扑描述（不探测，只格式化已读到的结果）。 */
    fun describeTopology(t: HostCpus.Topology): String = when {
        !t.known -> "宿主拓扑未知"
        t.big.isEmpty() -> "宿主 ${t.allowed.size} 核（读不到频率，来源 ${t.source}）"
        else -> "宿主 ${t.allowed.size} 核，其中大核 ${t.big.size} 个 " +
            "(${HostCpus.formatCpuList(t.big)})，来源 ${t.source}"
    }
}
