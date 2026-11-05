package com.android.gudao.iphoneos

import android.util.Log
import com.android.gudao.iphoneos.engine.AppLog
import com.android.gudao.iphoneos.engine.HostCpus
import com.android.gudao.iphoneos.engine.QemuAffinity
import com.android.gudao.iphoneos.engine.QemuFlags
import com.android.gudao.iphoneos.engine.QemuPreflight
import com.android.gudao.iphoneos.engine.QemuTopology
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.lang.ProcessBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the HackiOS QEMU `virt` (aarch64) command line and launches it. The command mirrors
 * `qemu/run.sh` + `qemu/run-ios.sh` from the upstream project exactly, with these Android-side
 * adaptations:
 *  - `-accel tcg` (software emulation, no KVM, no GPU / Secure Enclave) — works without root;
 *  - `-display none` instead of a GTK window; the screen is captured from the ramfb framebuffer
 *    through the HMP `screendump` command (see [QemuControl.captureFrame]);
 *  - `-serial file:` so the XNU/iOS console lands in a file the app can tail.
 *
 * Two hard-won details about failing silently:
 *
 *  1. `-serial file:` creates its file in `qemu_create_late_backends()`, i.e. only after the
 *     machine, the memory backend and every drive are set up. Any error before that leaves the
 *     file non-existent, so "QEMU started but serial.log is missing" means it died in option
 *     parsing, in the linker, or during machine init — never that the guest is quiet.
 *  2. QEMU's own stdout/stderr must be drained somewhere. A `ProcessBuilder` pipe that nobody
 *     reads takes the error message with it (and can block the child once 64 KB pile up), which
 *     is exactly how a 0-byte library in the bundle turned into "no log at all".
 */
class QemuRunner(private val runDir: File) {

    private var process: Process? = null
    private var drain: Thread? = null

    /**
     * 最近一次启动时 CPU 亲和性包装是否真的生效。0.26.0 由 AppViewModel 读进 perf-stats，
     * 让「宿主算力池」这一档在真机日志里可核验，而不是界面上显示 8 核、底下一声不响降级。
     */
    @Volatile
    var lastAffinityApplied: Boolean = false
        private set

    /** 0.29.5：本次真正生效的亲和性掩码（如 `4-7`）；降级时为空串。UI/日志直接展示它。 */
    @Volatile
    var lastAffinityMask: String = ""
        private set

    /**
     * 0.29.2：本次启动的资源预算（guest 内存 / TB 缓存 / TCG 模式 / 宿主 MemAvailable /
     * 可用存储）。QEMU 若在打开 `-serial` 之前就 exit=1，qemu.log 往往为空，这张「预算」
     * 就是唯一能解释失败现场的证据，失败卡片会把它原样展示出来。
     */
    @Volatile
    var lastStartSummary: String = ""
        private set

    data class Prepared(
        val payload: File,
        val ramImg: File,
        val serialLog: File,
        val systemSize: Long
    )

    /** The XNU/iOS console, written by QEMU itself through `-serial file:`. */
    fun serialLog(): File = File(runDir, "serial.log")

    /** QEMU's own stdout+stderr (its version banner on success, every startup error on failure). */
    fun qemuLog(): File = File(runDir, "qemu.log")

    /**
     * Build payload.bin (XNUP) and ram.img from the iOS images.
     *
     * 0.24.0：两个产物**分别按各自的脏标记重建**。改核数只影响 payload.bin 里的 `cpus=N`，
     * 不该重拷 2.5 GB 的 ram.img —— 那是旧版「调一次参数等好几分钟」的主因，也是把启动
     * 压进 3 分钟预算最实在的一刀。[rev] 是已发布镜像的版本号（[Constants.IMAGE_REV]）。
     */
    fun prepare(
        systemApfs: File,
        rootApfs: File,
        sessionSecs: Int = Constants.DEFAULT_SESSION_SECS,
        smp: Int = Constants.SMP_DEFAULT,
        extraBootArgs: String = "",
        rev: Int = Constants.IMAGE_REV,
    ): Prepared {
        require(rootApfs.exists()) { "root.apfs missing: ${rootApfs.absolutePath}" }
        require(systemApfs.exists()) { "system.apfs missing: ${systemApfs.absolutePath}" }

        val kernel = File(runDir, "kernel")
        val payload = File(runDir, "payload.bin")
        val ramImg = File(runDir, "ram.img")
        val serialLog = serialLog()

        QemuPreflight.migrateLegacyStamp(runDir)

        // 1) ram.img: system image at the loader's resident offset (sparse). 只在镜像真的变了
        //    或首次部署时重建；改核数 / 改 boot-args 都不会走到这里。
        val needRam = QemuPreflight.needsRamImage(runDir, systemApfs, rev)
        val needPayload = QemuPreflight.needsPayload(runDir, systemApfs, rootApfs, smp, rev, extraBootArgs)
        if (needRam) {
            RamImageBuilder.build(systemApfs, ramImg)
            QemuPreflight.writeRamStamp(runDir, systemApfs, rev)
        }
        val systemSize = systemApfs.length()

        // 2) payload.bin: XNU kernel + root ramdisk + boot-args, in XNUP format.
        // `cpus=N` 必须和 start() 交给 QEMU 的 -smp vCPU 总数严格一致，否则 XNU 只带起部分核。
        // 0.24.0 起统一由 QemuTopology 归一化，避免两处各自 coerceIn 造成不一致。
        // fbscale = 每个“点”画几个像素。3 是 iPhone X 面板的原生倍率（375×812 点 →
        // 1125×2436 像素），也是 ramfb 的尺寸；1 会让 WindowServer 只建 375×812 的帧缓冲，
        // 合成结果落不进 1125×2436 的 ramfb，屏幕全黑（2026-11-04 的真机日志：
        // mg_test: main-screen-scale = +1.0、CAWindowServerDisplay ... LCD 375x812）。
        if (needPayload) {
            // 0.26.5：追加经同代项目 ios-sweet（iOS 16.7 已出主屏）验证的启动参数，提升
            // 慢启动下的存活率与守护进程注册成功率。均为 XNU 忽略未知键的安全参数：
            //  - wdt=-1 wdt_test=no_user_checkin：关掉硬件看门狗，避免 loader_return_after=1200
            //    叠加 lsd 自旋 / 数据迁移慢导致的内核看门狗误杀（"卡死/重启" 的元凶之一）；
            //  - launchd_unsecure_cache=1：launchd 跳过 AMFI 校验直接接纳 job cache，让
            //    mobileactivationd 等按需守护进程在精简镜像里也能注册（直指 lsd 风暴根因）。
            val stabilityArgs = "wdt=-1 wdt_test=no_user_checkin launchd_unsecure_cache=1"
            // virtio_net=1：上游 run-darwin.sh 在开网时用它让 XNU 的 virtio-net 驱动真正接管
            // en0。少了这一条，QEMU 侧网卡与 ROM 都在，但 guest 只停在 "virtio network device
            // in slot …"，接口不 attach、拿不到 DHCP，表现成 Safari「未连接互联网」。
            val cmdline =
                "loader_return_after=$sessionSecs epoch=${System.currentTimeMillis() / 1000} " +
                    "cpus=${QemuTopology.cpusBootArg(smp)} virtio_input=1 virtio_net=1 " +
                    "gl=1 fbscale=3 plain_vibrancy=1 " +
                    stabilityArgs +
                    // Diagnostic boot-args come last so they win over the managed ones (XNU keeps the
                    // final value of a repeated key). QemuFlags already refused cpus=/rd=/epoch=.
                    (if (extraBootArgs.isBlank()) "" else " " + extraBootArgs.trim())
            val rdres = systemSize.toString(16)
            // Streamed: the old call passed `kernel.readBytes()` and `rootApfs.readBytes()` into an
            // in-memory packer, i.e. ~3× the ramdisk in RAM at once. That is an OOM on a phone.
            XnupPacker.packTo(
                dest = payload,
                kernel = kernel,
                ramdisk = rootApfs,
                cmdline = cmdline,
                rdres = rdres,
            )
            QemuPreflight.writeStamp(runDir, systemApfs, rootApfs, smp, rev, extraBootArgs)
        }
        Log.i(
            TAG,
            "prepare: ram=${if (needRam) "重建" else "复用"} payload=${if (needPayload) "重建" else "复用"} " +
                "payload=${payload.length()}B ram.img=${ramImg.length()}B system=$systemSize smp=$smp",
        )
        return Prepared(payload, ramImg, serialLog, systemSize)
    }

    /** 本次 prepare 是否会重拷 2.5 GB 的 ram.img；供时间线标注「复用缓存」。 */
    fun willRebuildRam(systemApfs: File, rev: Int = Constants.IMAGE_REV): Boolean =
        QemuPreflight.needsRamImage(runDir, systemApfs, rev)

    /**
     * Walk the bundle's DT_NEEDED closure and return what cannot be resolved out of
     * `qemu/lib/`. Empty means the exec will get as far as QEMU's own main().
     *
     * This exists because a zip cannot carry symlinks: the first bundle stored `libz.so.1`,
     * `libglib-2.0.so.0` and 14 others as links to versioned files that were never packed, so
     * Android's ZipInputStream produced 0-byte stubs. The linker rejects those, execve fails, and
     * nothing is printed anywhere the app can see — the symptom was "QEMU 已启动" with no log.
     */
    fun auditBundle(): List<String> {
        val bin = File(runDir, Constants.QEMU_BIN_REL)
        val libDir = File(runDir, Constants.QEMU_LIB_REL)
        if (!bin.isFile) return listOf("qemu-system-aarch64（二进制不存在）")
        if (bin.length() == 0L) return listOf("qemu-system-aarch64（0 字节，资源包解压不完整）")
        val missing = Elf.missingClosure(bin, libDir, SYSTEM_LIBS).toMutableList()
        // Option ROMs are resolved through the datadir given by -L, never from DT_NEEDED, so the
        // ELF walk above cannot see them. QEMU 11 aborts on a missing one *after* opening the
        // serial chardev, which is why the only evidence is "serial.log empty, exit=1".
        val share = File(runDir, Constants.QEMU_SHARE_REL)
        for (rom in REQUIRED_ROMS) {
            val f = File(share, rom)
            if (!f.isFile) missing.add("$rom（qemu/share/qemu 里没有）")
            else if (f.length() == 0L) missing.add("$rom（0 字节）")
        }
        // 两片 pflash 必须是 virt 机型的 64 MiB，否则 QEMU 在 chardev 初始化之前就报
        // "pflash: file too small" 并 exit=1 —— 这正好是「串口文件根本没生成」的形态。
        // 截断通常来自上一次拷贝时存储写满（deploy 用 runCatching 拷贝，失败不会抛出）。
        val pflash = PFLASH_BYTES
        for (name in listOf(Constants.ASSET_UEFI, Constants.ASSET_VARS)) {
            val f = File(runDir, name)
            if (!f.isFile) missing.add("$name（不存在）")
            else if (f.length() != pflash) {
                missing.add("$name 尺寸异常（${f.length()}B，应为 ${pflash}B）：重新部署资源以重拷固件")
            }
        }
        return missing
    }

    /** Launch QEMU. Call [prepare] first. The qemu binary lives in the extracted Termux bundle
     *  and is run with LD_LIBRARY_PATH pointing at the bundle's lib/ directory. */
    /**
     * Launch QEMU. Call [prepare] first. The qemu binary lives in the extracted Termux bundle
     *  and is run with LD_LIBRARY_PATH pointing at the bundle's lib/ directory.
     *  @param smp Guest vCPU 数（`-smp N`）；the kernel side is driven by the
     *  `cpus=N` boot-arg baked into the payload by [prepare].
     *  @param hostCores 宿主算力池核数：只影响 `tb-size` 规格与（自检通过时的）CPU 亲和性
     *  掩码，不改变 Guest vCPU 数，也不进 payload / ram.img，所以调它**零重建成本**。
     */
    fun start(
        smp: Int = Constants.SMP_DEFAULT,
        netEnabled: Boolean = Constants.NET_DEFAULT,
        dnsServers: List<String> = emptyList(),
        extraFlags: String = "",
        mttcg: Boolean = Constants.MTTCG_DEFAULT,
        hostCores: Int = Constants.HOST_CORES_DEFAULT,
        /**
         * guest 物理内存（GB），默认 [Constants.RAM_GB]。
         * 不要随手调小：system.apfs 从 0xFC000000 起驻留约 2.32 GiB，结束于约 6.26 GiB，
         * 6G 会把它推出 RAM —— 保守档重试只降 TB 缓存与 TCG 线程，不动这里。
         */
        ramGb: Long = Constants.RAM_GB,
        /** TB 翻译缓存（MiB）；null 时按宿主算力池规格（默认 8 核 → 1360 MiB）。 */
        tbMiB: Int? = null,
    ) {
        val qemu = File(runDir, Constants.QEMU_BIN_REL)
        val libDir = File(runDir, Constants.QEMU_LIB_REL)
        require(qemu.exists() && qemu.canExecute()) {
            "qemu binary missing or not executable: ${qemu.absolutePath}. " +
                "Deploy the qemu-android-bundle.zip asset first (see README.md)."
        }
        if (!libDir.isDirectory) {
            Log.w(TAG, "qemu lib dir not found: ${libDir.absolutePath}; running without LD_LIBRARY_PATH")
        }

        // Before spending a minute on QEMU's own startup: prove the exec can actually happen.
        val broken = auditBundle()
        if (broken.isNotEmpty()) {
            throw IllegalStateException(
                "qemu 运行资源不完整（${broken.size} 项）：${broken.take(6).joinToString()}。\n" +
                    "这是资源包问题，不是启动参数问题：请重新安装 APK，点「部署预构件」让 qemu/ 重新解压。"
            )
        }

        // 宿主内存预检（上游 run.sh 同款）：guest 要 7 GB，Android 的 LMK / phantom killer 会
        // 在 MemAvailable 不足时直接把 QEMU 杀掉（exit=137），用户的感受是「启动到一半突然没了」。
        // 提前拒绝比事后解释便宜：这里把「神秘消失」变成一条可读错误。读不到 /proc/meminfo 就跳过。
        val availKb = memAvailableKb()
        if (availKb > 0 && availKb < HOST_MEM_MIN_KB) {
            throw IllegalStateException(
                "宿主可用内存不足：${availKb / 1024} MB < ${HOST_MEM_MIN_KB / 1024} MB。" +
                    "QEMU 的 guest 需要约 ${ramGb} GB，Android 会先杀掉高占用进程" +
                    "（表现就是 exit=137 / 无日志消失）。\n请关闭其它应用后重试，或重启手机再启动。",
            )
        }

        val shareDir = File(runDir, Constants.QEMU_SHARE_REL)
        // Re-validated here as well: the value comes from persisted settings and may have been
        // written by an older build. A bad flag must fail with a readable reason before QEMU starts,
        // not as "exit code 1" ten seconds later.
        val extra = QemuFlags.parseDiagnostics(extraFlags)
        if (!extra.ok) {
            throw IllegalStateException("QEMU 诊断参数不合法：${extra.error}")
        }

        // 单线程 TCG 档沿用 0.26.0 的 384 MiB 基线（不算力池），只有保守档重试显式传 tbMiB。
        val effectiveTb = tbMiB
            ?: if (mttcg) QemuTopology.tbSize(hostCores) else QemuTopology.TB_SIZE_BASE
        val freeMb = runCatching { runDir.usableSpace / (1024L * 1024L) }.getOrDefault(-1L)
        lastStartSummary = buildString {
            append("本次启动：guest=${ramGb}G，TB=$effectiveTb MiB，TCG=")
            append(if (mttcg) "多线程(MTTCG)" else "单线程")
            append("，vCPU=$smp，宿主算力池=$hostCores 核")
            append("；宿主 MemAvailable=").append(if (availKb > 0) "${availKb / 1024} MB" else "未知")
            append("，run 目录可用存储=").append(if (freeMb >= 0) "$freeMb MB" else "未知")
            append("；payload.bin=").append(File(runDir, "payload.bin").length() / 1024).append(" KB")
            append("，ram.img=").append(File(runDir, "ram.img").length() / 1024 / 1024).append(" MB")
        }
        AppLog.i(TAG, lastStartSummary)
        // QEMU 的 guest 内存是 memory-backend-file（share=off），页面被写到 ram.img 时真的吃
        // 宿主存储：可用空间不足会以 SIGBUS 收场，看起来同样像「莫名退出」。只记进预算，
        // 不在这里拒绝 —— 拒绝会让本来能启动的机器彻底没法试。
        if (freeMb >= 0 && freeMb < effectiveTb / 1024 + ramGb * 1024 - 1200) {
            AppLog.w(TAG, "存储余量偏低（$freeMb MB）：guest 会向 ram.img 写入约 $ramGb GB，可能中途失败")
        }

        // 0.26.0 宿主算力池：先自检再决定是否加亲和性前缀。降级时返回空列表，
        // cmd[0] 仍是 qemu 本身 —— 与 0.25.0 完全一致的零回归路径。
        // 0.29.5：先读宿主机真实拓扑（/proc/self/status 的 Cpus_allowed_list + cpufreq），
        // 再决定钉哪些核。0.26.0 的 `0-(N-1)` 在 big.LITTLE 上常常正好是小核，
        // 溢出系统 cpuset 时又会整体降级——这两点都由 HostCpus 处理。
        val hostTopology = HostCpus.read()
        val affinity = QemuAffinity.resolve(hostCores, topology = hostTopology)
        lastAffinityApplied = affinity.command != null
        lastAffinityMask = affinity.mask
        AppLog.i(TAG, "宿主算力池 $hostCores：${affinity.note}")
        AppLog.i(TAG, "宿主拓扑：${QemuAffinity.describeTopology(hostTopology)}")

        val cmd = mutableListOf<String>().apply {
            // 前缀里已经包含 taskset 的语法（util-linux 的 -c / toybox 的十六进制），原样使用。
            addAll(affinity.prefix)
            add(qemu.absolutePath)
            // Datadir: where QEMU looks up option ROMs by name (efi-virtio.rom, vgabios-ramfb.bin).
            // Compiled-in default points at the Termux prefix, which does not exist on Android.
            addAll(listOf("-L", shareDir.absolutePath))
            addAll(listOf("-M", "virt,gic-version=3"))
            addAll(listOf("-cpu", "cortex-a76"))
            // 0.26.0：`-smp` 只由 Guest vCPU 决定（XNU 稳定性），`-accel` 的 TB 缓存
            // 由宿主算力池决定（吞吐）。二者解耦：2 个 vCPU 也能拿到 8 核规格的翻译缓存，
            // 减少重复翻译与 CPU 空转。参数由 QemuTopology 统一生成（可 JVM 单测）。
            addAll(listOf("-smp", QemuTopology.smpArg(smp, mttcg)))
            addAll(listOf("-m", "${ramGb}G"))
            addAll(listOf("-accel", QemuTopology.accelArgWith(mttcg, effectiveTb)))
            // Virtual networking (default ON): user-mode (slirp) NAT. The guest gets a DHCP
            // address and reaches the outside world through QEMU's built-in NAT -- no host bridge
            // or root required, which is the only viable path on Android. efi-virtio.rom ships in
            // the bundle (same -L dir as vgabios-ramfb.bin, already proven working) so the
            // virtio-net device initializes instead of erroring on a missing ROM.
            //
            // `dns=` is not optional on Android. slirp answers the guest's DNS queries (sent to
            // its virtual resolver at 10.0.2.3) by forwarding them to the *host's* resolver, which
            // it learns by reading /etc/resolv.conf. Android has no readable resolv.conf -- name
            // resolution there goes through netd over a socket -- so without this the guest gets
            // an interface, an IP and a default route, yet every name lookup times out. The caller
            // passes the live servers from ConnectivityManager.
            // `dns=` 只能给**一个**地址。QEMU 按逗号切分 -netdev 选项，写 `dns=a,b` 时 b 会被
            // 当成另一个选项名，先警告 "short-form boolean option 'b'" 再报
            // "Invalid parameter 'b'"，机器初始化阶段直接 exit=1（0.29.2 实测踩到的就是这个）。
            // 多个 DNS 在这里没有替代写法，取宿主第一个可用解析器即可。
            if (netEnabled) {
                val netdev = userNetdevArg(dnsServers)
                Log.i(TAG, "start: netdev=$netdev")
                addAll(listOf("-netdev", netdev))
                addAll(listOf("-device", "virtio-net-device,netdev=net0"))
            } else {
                addAll(listOf("-nic", "none"))
            }
            addAll(listOf("-drive", "if=pflash,format=raw,readonly=on,file=${File(runDir, Constants.ASSET_UEFI).absolutePath}"))
            addAll(listOf("-drive", "if=pflash,format=raw,file=${File(runDir, Constants.ASSET_VARS).absolutePath}"))
            addAll(listOf("-drive", "file=fat:rw:${File(runDir, "esp").absolutePath},format=raw,if=virtio"))
            addAll(listOf("-device", "loader,file=${File(runDir, "payload.bin").absolutePath},addr=0x${Constants.LOADER_ADDR.toString(16)},force-raw=on"))
            addAll(listOf("-object", "memory-backend-file,id=ram,size=${ramGb}G,mem-path=${File(runDir, "ram.img").absolutePath},share=off"))
            addAll(listOf("-machine", "memory-backend=ram"))
            addAll(listOf("-global", "virtio-mmio.force-legacy=false"))
            addAll(listOf("-device", "virtio-tablet-device"))
            addAll(listOf("-device", "virtio-keyboard-device"))
            // Display device: `ramfb`, exactly like the upstream project's run.sh. It has no
            // xres/yres properties (QEMU rejects them outright) and needs none: the loader
            // reshapes it to the iPhone X panel over the fw_cfg "etc/ramfb" DMA channel, so the
            // GOP ends up at 1125x2436 without the command line saying so. 2D only, software
            // rendered -- the "画面全靠 CPU" setup, no GPU needed. Host-verified end to end:
            // serial prints "display 1125 x 2436 at ..." and screendump returns a 1125x2436 PPM.
            //
            // The loader only got there after a real bug fix: its `dma` descriptor was not
            // `volatile`, so clang folded `if (dma.control != 0)` into "always true" and the
            // loader bailed out believing the DMA had failed. The GOP's 800x600 mode then stayed
            // in place while iOS rendered into a framebuffer it was told was 1125x2436 -- a
            // black screen. assets/loader.efi is rebuilt from the same source with `volatile`.
            addAll(listOf("-device", "ramfb"))
            addAll(listOf("-monitor", "tcp:127.0.0.1:${Constants.MON_PORT},server,nowait"))
            addAll(listOf("-qmp", "tcp:127.0.0.1:${Constants.QMP_PORT},server,nowait"))
            addAll(listOf("-display", "none"))
            addAll(listOf("-serial", "file:${serialLog().absolutePath}"))
            addAll(extra.values)
            addAll(listOf("-no-reboot"))
        }
        Log.i(TAG, "start: ${cmd.joinToString(" ")}")
        AppLog.i(TAG, "启动 QEMU：${QemuTopology.describe(smp, hostCores, mttcg)} " +
            "亲和性=${if (lastAffinityApplied) "已启用 ${lastAffinityMask}" else "降级（仅 TB 缓存）"} " +
            "net=$netEnabled。${cmd.joinToString(" ")}")
        // Keep the exact argv next to the log: the console panel can show what was attempted even
        // when QEMU dies before writing a single byte.
        runCatching { File(runDir, "qemu-cmdline.txt").writeText(cmd.joinToString(" ")) }

        val pb = ProcessBuilder(cmd)
        pb.directory(runDir)
        pb.redirectErrorStream(true)
        // Point the Android dynamic linker at the bundled shared libraries.
        if (libDir.isDirectory) {
            pb.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
        }
        // `-serial file:` 只有走到 chardev 初始化才会创建/截断 serial.log。若不清掉上一轮的文件，
        // 「serial.log 存在」会把「QEMU 在打开串口之前早退」误判成「串口已建立」，自愈重试
        // （0.29.2）也就永远不会触发。删除前先让调用方做完 EFI Shell 判定；删除后，
        // 「文件存在」就等价于「QEMU 真的走到串口初始化」。
        runCatching { serialLog().delete() }
        val log = qemuLog()
        process = pb.start()
        AppLog.i(TAG, "QEMU 进程已启动")
        // Drain stdout+stderr into qemu.log. Without this the child's error message stays in an
        // unread pipe and the process can even block once the 64 KB buffer fills.
        val p = process!!
        drain = Thread({
            try {
                FileOutputStream(log, false).use { fos ->
                    val buf = ByteArray(8192)
                    val ins = p.inputStream
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        fos.flush()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "log drain ended: ${t.message}")
            }
        }, "qemu-log").apply {
            isDaemon = true
            start()
        }
    }

    fun isRunning(): Boolean = process?.isAlive == true

    /** Exit status once the process is gone, null while it is still running (or never started). */
    fun exitCode(): Int? {
        val p = process ?: return null
        if (p.isAlive) return null
        return runCatching { p.exitValue() }.getOrNull()
    }

    fun stop() {
        val p = process
        if (p != null) {
            AppLog.w(TAG, "停止 QEMU：此前存活=${p.isAlive}，退出码=${runCatching { p.exitValue() }.getOrNull()}")
        }
        p?.destroyForcibly()
        process = null
    }

    private companion object {
        private const val TAG = "QemuRunner"

        /** `-M virt` 的两片 pflash 固定 64 MiB，与 assets 里 QEMU_EFI.fd / QEMU_VARS.fd 一致。 */
        private const val PFLASH_BYTES = 64L * 1024 * 1024

        /** Provided by Android's own linker namespace, never by the Termux bundle. */
        val SYSTEM_LIBS = setOf(
            "libc.so", "libm.so", "libdl.so", "liblog.so", "libstdc++.so", "libandroid.so"
        )

        /**
         * Option ROMs this command line cannot start without. `efi-virtio.rom` would be requested
         * by the machine's default virtio-net device, `vgabios-ramfb.bin` by `-device ramfb`.
         * `-nic none` keeps the first one away, but the ramfb device really does load the second
         * one, so it must be in the datadir. The audit checks both either way.
         */
        val REQUIRED_ROMS = listOf("efi-virtio.rom", "vgabios-ramfb.bin")

        /**
         * 启动前宿主至少要有的 MemAvailable（KiB）。上游 run.sh 用 3000 MB：低于它时 Android
         * 会在 guest 起来前就杀掉 QEMU（exit=137）。读不到 /proc/meminfo 时返回 -1，跳过检查。
         */
        private const val HOST_MEM_MIN_KB = 3_000_000L

        private fun memAvailableKb(): Long = runCatching {
            File("/proc/meminfo").readLines()
                .firstOrNull { it.startsWith("MemAvailable:") }
                ?.filter(Char::isDigit)
                ?.toLongOrNull()
                ?: -1L
        }.getOrDefault(-1L)
    }
}

/**
 * Just enough ELF64 to read DT_NEEDED out of the dynamic section of an aarch64 object: program
 * headers -> PT_DYNAMIC -> DT_NEEDED offsets -> PT_DYNAMIC's DT_STRTAB. No section headers are
 * required, so it works on stripped libraries too.
 */
internal object Elf {

    /**
     * Walk [bin]'s DT_NEEDED closure through [libDir] and return every name that cannot be
     * satisfied. A file that exists but is empty counts as missing -- that is precisely what a
     * zipped symlink becomes on Android, and it is what broke the first qemu bundle.
     */
    fun missingClosure(bin: File, libDir: File, systemLibs: Set<String>): List<String> {
        val missing = LinkedHashSet<String>()
        val seen = HashSet<String>()
        val queue = ArrayDeque<File>()
        queue.add(bin)
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            if (!seen.add(f.absolutePath)) continue
            for (name in needed(f)) {
                val p = File(libDir, name)
                when {
                    p.isFile && p.length() > 0 -> queue.add(p)
                    name in systemLibs -> Unit
                    p.isFile -> missing.add("$name（0 字节）")
                    else -> missing.add(name)
                }
            }
        }
        return missing.toList()
    }

    fun needed(file: File): List<String> = try {
        RandomAccessFile(file, "r").use { raf ->
            val ident = ByteArray(64)
            raf.readFully(ident)
            if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() ||
                ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()
            ) {
                return emptyList()
            }
            if (ident[4] != 2.toByte()) return emptyList()          // ELFCLASS64 only
            val hdr = ByteBuffer.wrap(ident).order(ByteOrder.LITTLE_ENDIAN)
            val phoff = hdr.getLong(0x20)
            val phentsize = hdr.getShort(0x36).toInt() and 0xffff
            val phnum = hdr.getShort(0x38).toInt() and 0xffff
            if (phoff <= 0 || phentsize < 56 || phnum <= 0 || phnum > 512) return emptyList()

            val phTab = ByteArray(phentsize * phnum)
            raf.seek(phoff)
            raf.readFully(phTab)
            val ph = ByteBuffer.wrap(phTab).order(ByteOrder.LITTLE_ENDIAN)

            var dynOff = -1L
            val loads = ArrayList<LongArray>()
            for (i in 0 until phnum) {
                val b = i * phentsize
                when (ph.getInt(b)) {
                    1 -> loads.add(                                       // PT_LOAD
                        longArrayOf(ph.getLong(b + 16), ph.getLong(b + 8), ph.getLong(b + 32))
                    )
                    2 -> dynOff = ph.getLong(b + 8)                       // PT_DYNAMIC
                }
            }
            if (dynOff < 0) return emptyList()

            raf.seek(dynOff)
            val ent = ByteArray(16)
            val db = ByteBuffer.wrap(ent).order(ByteOrder.LITTLE_ENDIAN)
            var strtab = -1L
            val needed = ArrayList<Long>()
            var guard = 0
            while (guard++ < 4096) {
                try {
                    raf.readFully(ent)                                    // short reads would corrupt db
                } catch (e: java.io.EOFException) {
                    break                                                 // table ran to end of file
                }
                val tag = db.getLong(0)
                val value = db.getLong(8)
                if (tag == 0L) break                                      // DT_NULL
                if (tag == 1L) needed.add(value)                          // DT_NEEDED
                if (tag == 5L) strtab = value                             // DT_STRTAB
            }
            if (strtab < 0 || needed.isEmpty()) return emptyList()

            val strOff = vaddrToOffset(strtab, loads) ?: return emptyList()
            needed.mapNotNull { readString(raf, strOff + it) }
        }
    } catch (t: Throwable) {
        Log.w("Elf", "cannot read ${file.name}: ${t.message}")
        emptyList()
    }

    private fun vaddrToOffset(vaddr: Long, loads: List<LongArray>): Long? {
        for (l in loads) {
            val (v, off, size) = Triple(l[0], l[1], l[2])
            if (vaddr >= v && vaddr < v + size) return off + (vaddr - v)
        }
        return null
    }

    private fun readString(raf: RandomAccessFile, off: Long): String? = try {
        raf.seek(off)
        val buf = ByteArray(256)
        val n = raf.read(buf)
        if (n <= 0) null else {
            var end = 0
            while (end < n && buf[end] != 0.toByte()) end++
            String(buf, 0, end, Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        null
    }
}

/** `-netdev user` 参数。`dns=` 只接受一个地址：写成 `dns=a,b` 会被解析成两个选项而 exit=1。 */
internal fun userNetdevArg(dnsServers: List<String>): String {
    val dns = dnsServers.asSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.contains(',') && !it.contains(' ') }
    return if (dns == null) "user,id=net0" else "user,id=net0,dns=$dns"
}
