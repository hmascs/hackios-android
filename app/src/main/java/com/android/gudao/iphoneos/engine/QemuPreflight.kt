package com.android.gudao.iphoneos.engine

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Small pre-flight checks that turn "QEMU started and died" into a precise reason *before* QEMU is
 * even spawned.
 *
 * 0.24.0：**把两个昂贵的产物分开缓存**。
 *
 * 旧版把 `smp` 塞进同一个 stamp，于是「把 1 核改成 6 核」这种纯参数调整也会让 `needsPrepare`
 返回 true，接着 [QemuRunner.prepare] 重新执行 RamImageBuilder —— 把 2.5 GB 的 system.apfs
 * 再拷一遍进 12 GiB 稀疏文件。参数变了要重建的只有 payload.bin 里那几十字节的 `cpus=N`，
 * ram.img 的内容完全没变（system.apfs 决定了它，核数决定不了）。
 *
 * 现在：
 *  - `.ram-stamp` 只由 system.apfs + 镜像 rev 决定 → 决定要不要重拷 2.5 GB；
 *  - `.payload-stamp` 在此基础上再加 smp / bootArgs / cmdline profile → 决定要不要重打包；
 *  - [needsPrepare] 是两者的 OR，但 [QemuRunner.prepare] 会分别查询，只重建真正脏的那一个。
 *
 * 这是「反复调参 / 自动降级重启」场景下最大的一笔提速：改核数从「2.5 GB 拷贝」变成
 * 「重打包一个 payload」，省下的正是往 3 分钟预算里挤的那部分时间。
 */
object QemuPreflight {

    /** True when something already accepts connections on [port] (a leftover QEMU, usually). */
    fun isListening(port: Int, timeoutMs: Int = 250): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs) }
        true
    } catch (_: Throwable) {
        false
    }

    fun busyPorts(): List<Int> = listOf(4445, 4444).filter { isListening(it) }

    /**
     * 托管启动参数的版本号。**改动 QemuRunner 里那段 cmdline（fbscale、gl、virtio_input…）
     * 就要 +1**：这些键是烘进 payload.bin 的，不换号的话老用户的缓存 payload 会继续用旧值，
     * 表现为「改了代码但设备上没变化」——fbscale=1 的黑屏就是这么留下来的。
     *
     * 0.24.0：+1 到 3。cmdline 本身没改，但多核架构把 `cpus=N` 的来源换成了
     * [QemuTopology.cpusBootArg]，且 MTTCG 档烘进的是拓扑化后的核数；让旧 payload 一律失效，
     * 避免 0.23.0 的缓存 payload 带着旧 cpus 值继续被六核机器加载。
     * 0.26.5：+1 到 4。QemuRunner 的托管 cmdline 追加了经 ios-sweet 验证的稳定性启动参数
     * （wdt=-1 / wdt_test=no_user_checkin / launchd_unsecure_cache=1），旧 payload 须失效以重烘。
     * 0.29.1：+1 到 5。kernel 资产换成上游 prebuilt/hakios-kernel（10-25 构建）；payload
     * stamp 不含 kernel 身份，用 profile 号强制重烘。
     */
    const val CMDLINE_PROFILE = 5

    /** ram.img 的内容只取决于系统镜像与镜像版本，与核数、启动参数、cmdline 版本无关。 */
    fun ramStampFor(systemApfs: File, rev: Int): String = buildString {
        append("sys=").append(systemApfs.length()).append(':').append(systemApfs.lastModified())
        append(";rev=").append(rev)
    }

    /** payload.bin 的内容包含 cpus=N、诊断 boot-args 与托管 cmdline，所以这些都进 stamp。 */
    fun payloadStampFor(
        systemApfs: File,
        rootApfs: File,
        smp: Int,
        rev: Int,
        bootArgs: String = "",
        profile: Int = CMDLINE_PROFILE,
    ): String = buildString {
        append("sys=").append(systemApfs.length()).append(':').append(systemApfs.lastModified())
        append(";root=").append(rootApfs.length()).append(':').append(rootApfs.lastModified())
        append(";smp=").append(smp)
        append(";rev=").append(rev)
        append(";prof=").append(profile)
        if (bootArgs.isNotBlank()) append(";boot=").append(bootArgs.trim())
    }

    /** 兼容旧调用方与单测：等价于 [payloadStampFor]。 */
    fun stampFor(
        systemApfs: File,
        rootApfs: File,
        smp: Int,
        rev: Int,
        bootArgs: String = "",
        profile: Int = CMDLINE_PROFILE,
    ): String = payloadStampFor(systemApfs, rootApfs, smp, rev, bootArgs, profile)

    /** 是否必须重拷 2.5 GB 的 ram.img。 */
    fun needsRamImage(runDir: File, systemApfs: File, rev: Int): Boolean {
        val ram = File(runDir, "ram.img")
        if (!ram.isFile || ram.length() == 0L) return true
        val stamp = File(runDir, ".ram-stamp")
        if (!stamp.isFile) return true
        return runCatching { stamp.readText().trim() != ramStampFor(systemApfs, rev) }
            .getOrDefault(true)
    }

    /** 是否必须重打包 payload.bin。 */
    fun needsPayload(
        runDir: File,
        systemApfs: File,
        rootApfs: File,
        smp: Int,
        rev: Int,
        bootArgs: String = "",
        profile: Int = CMDLINE_PROFILE,
    ): Boolean {
        val payload = File(runDir, "payload.bin")
        if (!payload.isFile || payload.length() == 0L) return true
        val stamp = File(runDir, ".payload-stamp")
        if (!stamp.isFile) return true
        return runCatching {
            stamp.readText().trim() != payloadStampFor(systemApfs, rootApfs, smp, rev, bootArgs, profile)
        }.getOrDefault(true)
    }

    /** 总开关：任一产物脏了就要走 prepare（具体重建哪一个由 [QemuRunner] 分别查询）。 */
    fun needsPrepare(
        runDir: File,
        systemApfs: File,
        rootApfs: File,
        smp: Int,
        rev: Int,
        bootArgs: String = "",
        profile: Int = CMDLINE_PROFILE,
    ): Boolean =
        needsRamImage(runDir, systemApfs, rev) ||
            needsPayload(runDir, systemApfs, rootApfs, smp, rev, bootArgs, profile)

    fun writeRamStamp(runDir: File, systemApfs: File, rev: Int) {
        runCatching { File(runDir, ".ram-stamp").writeText(ramStampFor(systemApfs, rev)) }
    }

    fun writeStamp(
        runDir: File,
        systemApfs: File,
        rootApfs: File,
        smp: Int,
        rev: Int,
        bootArgs: String = "",
        profile: Int = CMDLINE_PROFILE,
    ) {
        writeRamStamp(runDir, systemApfs, rev)
        runCatching {
            File(runDir, ".payload-stamp")
                .writeText(payloadStampFor(systemApfs, rootApfs, smp, rev, bootArgs, profile))
        }
    }

    /**
     * 从旧版单一 `.payload-stamp` 迁移：老 stamp 里含 smp/prof，不能当作 ram-stamp 用。
     * 直接删掉旧 stamp，让第一次 0.24.0 启动重建一次（之后就一直复用）。
     */
    fun migrateLegacyStamp(runDir: File) {
        val legacy = File(runDir, ".payload-stamp")
        val ramStamp = File(runDir, ".ram-stamp")
        if (legacy.isFile && !ramStamp.isFile) {
            runCatching { legacy.delete() }
        }
    }
}
