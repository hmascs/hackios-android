package com.android.gudao.iphoneos.engine

/**
 * Guest vCPU 拓扑与**宿主算力池**的唯一来源（0.26.0 起两者解耦）。
 *
 * 0.24.0 把「XNU 看到几个 CPU」和「宿主给 QEMU 多少算力」绑成同一个 `-smp N`：
 * `thread=multi` 下每个 vCPU 恰好一条宿主 TCG 线程，于是 N 越大 guest 的 SMP bringup /
 * IPI / 锁竞争越重，而宿主侧也只有 N 条翻译线程。荣耀 90 真机日志证实这会把 8 核吃满，
 * App 侧取帧/PPM 解码线程抢不到核，出现「取帧耗时 76430ms」。
 *
 * 0.26.0 把它们拆成两个正交维度：
 *  - **Guest vCPU（SMP 拓扑）**：`-smp N` + payload 里的 `cpus=N`。XNU 只见 N 个处理器，
 *    决定 SMP 稳定性；默认 2，是 bringup 最稳的甜点。
 *  - **宿主算力池（Host compute pool）**：QEMU 进程族允许调度到的物理核数（CPU 亲和性
 *    掩码）+ TCG 翻译缓存预算（`tb-size`）。默认 8。它不改变 guest 的 vCPU 数，只决定
 *    2 条 TCG 线程、I/O 线程、main-loop、以及 App 取帧线程能不能并行铺开。
 *
 * 诚实边界：TCG 是「每 vCPU 一条宿主翻译线程」，2 个 vCPU 的**并行计算上限仍是 2 核**，
 * 不存在 2 vCPU 并行吃满 8 核。放开算力池带来的收益是 (1) TB 缓存按 8 核规格分配，
 *  避免 2 vCPU 只拿到 384 MiB 而反复重译（这正是「减少 CPU 空转」的一层）；(2) QEMU 的
 *  辅助线程与 App 取帧线程不再挤在同一批核上；(3) guest 侧锁竞争与 IPI 大幅减少。
 *  Android 上亲和性还受系统 cpuset 约束，见 [affinityPrefix] 的降级说明。
 *
 * 这里保持纯 Kotlin（不碰 Android），整条参数链路可以在 JVM 单测里逐项断言。
 */
object QemuTopology {

    const val MIN_CORES = 1
    const val MAX_CORES = 8

    /**
     * 默认 Guest vCPU 数。0.26.0 从 6 降到 2：XNU 的 SMP bringup、IPI、pset 调度在 2 核下
     * 最稳，真机的 lsd/analyticsd 进程风暴也与 guest 并发度正相关。宿主算力由
     * [HOST_CORES_DEFAULT] 独立提供，不靠堆 vCPU。
     */
    const val GUEST_VCPU_DEFAULT = 2

    /** 0.24.0 云端验证过的六核并行档；保留为推荐上限与自动降核的参考，不再是默认值。 */
    const val STABLE_CORES = 6

    // ---- 宿主算力池 ----------------------------------------------------------------------------

    const val HOST_CORES_MIN = 2
    const val HOST_CORES_MAX = 8

    /**
     * 默认宿主算力池：允许 QEMU 进程族调度到 8 个物理核，并按 8 核规格给 TB 缓存。
     * 与 [GUEST_VCPU_DEFAULT] 无关，两个滑块互不牵连。
     */
    const val HOST_CORES_DEFAULT = 8

    /** 卡住自动降级的下限：guest 降到 2 核就不再往下，绝不退回 1 核单线程。 */
    const val FALLBACK_MIN_CORES = 2

    /** 单线程 TCG 时代的翻译缓存基线（MiB），也是 MTTCG 档的兜底最小值。 */
    const val TB_SIZE_BASE = 384

    /** 每个宿主算力核对应的翻译缓存（MiB）。8 核 → 1360 MiB。 */
    private const val TB_PER_CORE = 170

    fun normalize(cores: Int): Int = cores.coerceIn(MIN_CORES, MAX_CORES)

    fun normalizeHostCores(cores: Int): Int = cores.coerceIn(HOST_CORES_MIN, HOST_CORES_MAX)

    /** 每核约 170 MiB，不低于旧的 384 MiB；单核仍给 384。传宿主算力池核数。 */
    fun tbSize(cores: Int): Int = maxOf(TB_SIZE_BASE, normalize(cores) * TB_PER_CORE)

    /**
     * `-smp` 的值。MTTCG 档用显式拓扑；扁平拓扑只保留给「关掉多线程 TCG」的排障档。
     * 只由 Guest vCPU 数决定，与宿主算力池无关。
     */
    fun smpArg(cores: Int, mttcg: Boolean): String {
        val n = normalize(cores)
        return if (mttcg) "$n,sockets=1,cores=$n,threads=1" else n.toString()
    }

    /**
     * 0.24.0 的旧签名：TB 缓存随 vCPU 数放大。保留给已有调用方与单测，语义完全不变。
     */
    fun accelArg(cores: Int, mttcg: Boolean): String = accelArg(cores, cores, mttcg)

    /**
     * 0.26.0 解耦签名：`tb-size` 由**宿主算力池**驱动，而不是 vCPU 数。
     * 这样 2 个 vCPU 也能拿到 8 核规格的翻译缓存（1360 MiB），减少重复翻译与 CPU 空转；
     * 关掉 MTTCG 时仍回退到单线程基线。
     */
    fun accelArg(cores: Int, hostCores: Int, mttcg: Boolean): String =
        if (mttcg) accelArgWith(true, tbSize(normalizeHostCores(hostCores)))
        else accelArgWith(false, TB_SIZE_BASE)

    /**
     * 0.29.2：显式指定 TB 缓存（MiB）的版本，供「QEMU 打开串口之前就 exit=1」时的保守档
     * 自愈重试用。分配 1360 MiB 翻译缓存再叠加 7 GB guest 在低内存机型上可能直接失败，
     * 换 512 MiB + 单线程 TCG 是同一套参数里最省内存的组合。
     */
    fun accelArgWith(mttcg: Boolean, tbMiB: Int): String =
        if (mttcg) "tcg,thread=multi,tb-size=$tbMiB" else "tcg,tb-size=$tbMiB"

    /**
     * CPU 亲和性掩码，如 8 → `0-7`。它只限制「允许调度到哪些物理核」，不增加 vCPU。
     * 掩码以最低编号核开头，真机上由系统 cpuset 再求交集。
     */
    fun affinityMask(hostCores: Int): String {
        val n = normalizeHostCores(hostCores)
        return "0-${n - 1}"
    }

    /**
     * 0.29.5：按**实际选中的核**生成掩码。0.26.0 只会拼 `0-(N-1)`，在 big.LITTLE 上
     * 编号最小的核往往是小核，等于把 QEMU 钉死在小核上；溢出系统 cpuset 时又会整条
     * setaffinity 失败。现在的调用方先经 [HostCpus] 求出「允许 + 大核优先」的核集合，
     * 再交给这里格式化（`0-3,6,8`）。
     */
    fun affinityMask(cores: List<Int>): String = HostCpus.formatCpuList(cores)

    /**
     * 生成亲和性启动前缀：[affinityCommand] + `-c` + 掩码。
     *
     * [affinityCommand] 是可执行包装命令本身，例如 `["/system/bin/taskset"]`，或 toybox 形式
     * `["/system/bin/toybox", "taskset"]`。传 null / 空列表即**降级路径** —— 这是必须的：
     * Android 普通 app 的 `sched_setaffinity` 受系统 cpuset 约束，很多 ROM 也不提供
     * `taskset`。若包装命令 setaffinity 失败，它会直接退出、QEMU 根本不会被 exec，
     * 启动就整体失败。因此调用方必须先自检、只有自检通过才传非空命令；降级时 QEMU 照常
     * 启动并继承系统 cpuset，`tb-size` 这层算力预算仍 100% 生效。是否生效必须写进日志，
     * 不允许静默假装已挂载。
     */
    fun affinityPrefix(hostCores: Int, affinityCommand: List<String>?): List<String> =
        affinityPrefix(affinityCommand, affinityMask(hostCores))

    /**
     * 0.29.5：显式掩码版本。掩码为空表示「不需要/不能钉核」——例如系统本来就放行全部核，
     * 这时钉核只有风险没有收益，调用方应传空列表走降级路径。
     */
    fun affinityPrefix(affinityCommand: List<String>?, mask: String): List<String> =
        if (affinityCommand.isNullOrEmpty() || mask.isEmpty()) emptyList()
        else affinityCommand + listOf("-c", mask)

    /** 烘进 payload.bin 的 `cpus=N`，必须与 `-smp` 的 vCPU 总数严格一致。宿主算力池不进 payload。 */
    fun cpusBootArg(cores: Int): String = normalize(cores).toString()

    /**
     * 卡住时降一档：砍半，下限 [FALLBACK_MIN_CORES]。已经到底返回 null，让调用方停止降级，
     * 避免「卡住 → 重启 → 又卡住」的无限循环。只降 Guest vCPU，不动宿主算力池。
     */
    fun fallbackCores(cores: Int): Int? {
        val n = normalize(cores)
        if (n <= FALLBACK_MIN_CORES) return null
        return maxOf(FALLBACK_MIN_CORES, n / 2)
    }

    /** 0.24.0 兼容签名。 */
    fun describe(cores: Int, mttcg: Boolean): String = describe(cores, cores, mttcg)

    /** 一行给人看的档位描述，进 AppLog / MCP device_status / Toast。 */
    fun describe(cores: Int, hostCores: Int, mttcg: Boolean): String {
        val n = normalize(cores)
        val h = normalizeHostCores(hostCores)
        return if (mttcg) {
            "$n vCPU MTTCG / 宿主 $h 核算力池（thread=multi，拓扑 sockets=1/cores=$n/threads=1，" +
                "TB ${tbSize(h)} MiB，亲和性上限 ${affinityMask(h)}，启动时按真实拓扑重算）"
        } else {
            "$n 核 单线程 TCG（TB $TB_SIZE_BASE MiB，排障基线）"
        }
    }
}
