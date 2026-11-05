package com.android.gudao.iphoneos

import com.android.gudao.iphoneos.engine.QemuTopology

/**
 * Constants for the HackiOS-on-Android integration.
 *
 * This app is an Android *shell* around the HackiOS project (OOSEX GANG), which boots the
 * open-source XNU kernel of iOS 16.5 on a QEMU `virt` (aarch64) machine and runs the unmodified
 * iOS 16.5 user space on top. See README.md.
 */
object Constants {

    /** Application package name, as requested. */
    const val PACKAGE_NAME = "com.android.gudao.iphoneos"

    // ---- Apple CDN firmware (iPhone X, iOS 16.5 build 20F66) -------------------------------
    // Verified reachable (HTTP 200, 6,165,698,198 bytes). No Apple files are bundled in this app;
    // the IPSW is fetched straight from Apple's own CDN, the way the upstream project does it.
    const val IPSW_URL =
        "https://updates.cdn-apple.com/2023SpringFCS/fullrestores/032-85130/" +
            "6AB0D10A-8AF9-4F6C-8CF5-A1B315CABE88/iPhone10,3,iPhone10,6_16.5_20F66_Restore.ipsw"
    const val IPSW_FILENAME = "iPhone10,3,iPhone10,6_16.5_20F66_Restore.ipsw"
    const val IPSW_SHA256 = "c1091c401f67e3aa7cdfeb2cc19e6c79be3c957aae4e097a019e9ccea07d52f2"
    const val IPSW_SIZE_BYTES = 6165698198L

    /** The three disk images ios165/fetch.sh unpacks, as ZIP members of the IPSW, with the exact
     * byte counts read from Apple's own central directory (so a wrong or truncated file fails
     * fast instead of halfway through a 7 GB extraction). */
    val IPSW_MEMBERS: List<Triple<String, String, Long>> = listOf(
        Triple("096-04088-077.dmg", "rootfs.dmg", 4_728_546_953L),      // the root filesystem, APFS
        Triple("096-04666-078.dmg", "cryptex-os.dmg", 2_726_297_600L),  // the OS cryptex (dyld cache)
        Triple("096-03536-083.dmg", "cryptex-app.dmg", 10_485_760L),    // the App cryptex
    )
    /** Uncompressed bytes the app has to write out of the IPSW: 7.46 GB. */
    val IPSW_EXTRACT_BYTES: Long = IPSW_MEMBERS.sumOf { it.third }

    // ---- QEMU / boot geometry (mirrors hackios qemu/run-ios.sh & qemu/run.sh) ---------------
    const val RAM_GB = 7L
    const val FILE_SIZE = 12L shl 30          // ram.img total size (12 GiB)
    const val RAM_BASE = 0x40000000L
    const val RD_ADDR = 0xFC000000L
    val RAM_IMG_OFFSET = (RD_ADDR - RAM_BASE)  // system.apfs resident address inside ram.img

    // ---- 0.26.0 Guest vCPU / 宿主算力池解耦 -----------------------------------------------------
    // 0.24.0 把「XNU 看到几个 CPU」和「宿主给 QEMU 多少算力」绑成同一个 -smp N，于是想要宿主
    // 多核算力就得让 guest 也开多核，SMP bringup / IPI / 锁竞争随之变重，荣耀 90 日志里
    // 「取帧耗时 76430ms」正是 6 条 TCG 线程把 8 核吃满、App 取帧抢不到核的结果。
    //
    // 0.26.0 拆成两个正交维度（见 engine/QemuTopology.kt）：
    //  - Guest vCPU（SMP 拓扑）：`-smp N` + payload `cpus=N`，默认 2，只决定 XNU 稳定性；
    //  - 宿主算力池（CPU 亲和性 + TB 缓存规格）：默认 8，只决定 QEMU 进程族能调度到几个
    //    物理核、翻译缓存按几核规格分配，不改变 guest 的 vCPU 数。
    //
    // 云端 QEMU 8.2.2 实测（cloud-verify-025/qemu-decoupled/）：`taskset -c 0-7` 包装 +
    // `-smp 2,sockets=1,cores=2,threads=1` + `tcg,thread=multi,tb-size=1360` → QMP 实测
    // 2 个 vCPU、status=running、QEMU 全部线程 Cpus_allowed_list=0-7、帧缓冲可取。
    /** 默认 Guest vCPU = 2：XNU SMP bringup 最稳；宿主算力由 HOST_CORES_DEFAULT 提供。 */
    const val SMP_DEFAULT = QemuTopology.GUEST_VCPU_DEFAULT
    const val SMP_MIN = QemuTopology.MIN_CORES
    const val SMP_MAX = QemuTopology.MAX_CORES
    /**
     * 推荐 Guest vCPU 上限。超过它界面仍允许，但给出软提示：guest 侧并发越高，
     * launchd/SpringBoard 调度开销与真机 lsd 进程风暴越重。0.24.0 云端实测 6 核可并行，
     * 这里保留为「稳定档上限」而非默认值。
     */
    const val SMP_STABLE_MAX = QemuTopology.STABLE_CORES

    // ---- 宿主算力池（0.26.0 新增，与 SMP 正交）---------------------------------------------------
    const val HOST_CORES_DEFAULT = QemuTopology.HOST_CORES_DEFAULT
    const val HOST_CORES_MIN = QemuTopology.HOST_CORES_MIN
    const val HOST_CORES_MAX = QemuTopology.HOST_CORES_MAX

    /** 默认开启多线程 TCG；关掉即回到 0.23.0 的单线程基线，供排障对比。 */
    const val MTTCG_DEFAULT = true

    /** 卡住（画面长时间无变化）时自动降核重启一次，避免用户干等；见 AppViewModel 的降级保护。 */
    const val AUTO_FALLBACK_DEFAULT = true

    /**
     * 画面无变化超过这个时长就触发自动降级。
     *
     * 0.29.5 从 120 秒改成 15 分钟：实测这份镜像的合成器**没有停摆**，只是 Mesa softpipe 在
     * 1125x2436 上单帧 7–43 秒，第 3 帧要等约 10 分钟、整机进桌面要十几分钟。原来 2 分钟就
     * 降核重启，等于每 2 分钟把已经跑了很久的 guest 杀掉重来，永远到不了桌面。
     */
    const val STALL_FALLBACK_MS = 900_000L
    // Default ON: hand the guest a user-mode (slirp) virtual NIC so iOS can reach the outside
    // world through QEMU's built-in NAT. No host bridge / root required -- the only viable
    // networking path on Android. The user can still turn it off from the UI.
    const val NET_DEFAULT = true
    const val QMP_PORT = 4445                  // QEMU Machine Protocol (input / control)
    const val MON_PORT = 4444                  // HMP monitor (screendump)
    const val LOADER_ADDR = 0xF0000000L        // payload.bin load address

    // Phone screen within the guest display mode (HackiOS crops the top-left corner of this size).
    const val SCREEN_W = 1125
    const val SCREEN_H = 2436

    // Live display: poll the guest framebuffer over HMP `screendump` and repaint the SurfaceView.
    // 8 FPS, deliberately lower than the old 15: once the loader has negotiated 1125x2436, every
    // screendump writes an 8.2 MB PPM through the phone's flash, and 15 FPS meant ~123 MB/s of
    // writes that QEMU's single TCG thread then competes with. 8 FPS is still smooth for a boot
    // demo and halves that pressure. Raise it only if the decode path stops being the bottleneck.
    const val FB_FPS = 8
    const val FB_INTERVAL_MS = 1000L / FB_FPS          // 125 ms between frames

    const val DEFAULT_SESSION_SECS = 1200      // 20 minutes, like the upstream default

    // Asset file names shipped in app/src/main/assets.
    const val ASSET_KERNEL = "mach.development.bcm2837"
    const val ASSET_LOADER = "loader.efi"
    const val ASSET_UEFI = "QEMU_EFI.fd"
    const val ASSET_VARS = "QEMU_VARS.fd"

    // The Android aarch64 qemu binary is delivered as a self-contained Termux bundle: the
    // qemu-system-aarch64 ELF plus every shared library it needs, packaged as a zip asset and
    // extracted at runtime into <runDir>/qemu/ (binary at qemu/qemu-system-aarch64, libs at
    // qemu/lib/*.so*). The app execs the binary with LD_LIBRARY_PATH pointing at that lib dir.
    const val ASSET_QEMU_BUNDLE = "qemu-android-bundle.zip"
    const val QEMU_DIR_REL = "qemu"                       // the bundle's own top folder
    const val QEMU_BIN_REL = "qemu/qemu-system-aarch64"   // relative to runDirectory()
    const val QEMU_LIB_REL = "qemu/lib"                   // LD_LIBRARY_PATH for the bundled .so

    // QEMU's data directory (`-L`): the option ROMs it loads into the emulated machine. They are
    // separate files shipped by the distro's "qemu-common" package, not part of the binary, and
    // QEMU looks for them only under its datadir -- which on Android points at a Termux prefix
    // that does not exist. Missing them is fatal, not cosmetic: QEMU 11 stops with
    //   qemu-system-aarch64: failed to find romfile "efi-virtio.rom"
    // right after -serial has been opened, i.e. with an empty serial.log and exit code 1.
    const val QEMU_SHARE_REL = "qemu/share/qemu"

    // ---- On-device image build (HackiOS scripts + Termux toolchain) ---------------------------
    // hackios-scripts.zip: the project's own build scripts, laid out with the repository's
    // relative paths (ios165/, tools/, userland/, prebuilt/, xnu-env.sh, xnu-deps/host/bin/unzbm).
    // Extracted to <runDir>/hackios/.
    const val ASSET_SCRIPTS_BUNDLE = "hackios-scripts.zip"
    const val SCRIPTS_DIR_REL = "hackios"

    // hackios-toolchain.zip: Termux aarch64 python 3.14 + 7-Zip 26.04 + bash + coreutils +
    // findutils + sed (bin/, lib/, usr/lib/python3.14/), with the symlinks listed in
    // _symlinks.txt and recreated by AssetDeployer. Extracted to <runDir>/toolchain/.
    const val ASSET_TOOLCHAIN_BUNDLE = "hackios-toolchain.zip"
    const val TOOLCHAIN_DIR_REL = "toolchain"
    const val TOOLCHAIN_BIN_REL = "toolchain/bin"
    const val TOOLCHAIN_LIB_REL = "toolchain/lib"
    const val TOOLCHAIN_USR_REL = "toolchain/usr"         // PYTHONHOME

    // unzbm-probe.zip: two real LZBITMAP file pairs (a decmpfs header plus its resource fork)
    // taken from the App cryptex. Deployed to <runDir>/probe/ and fed to unzbm by the
    // self-test, so the finisher is proven against genuine compressed data rather than merely
    // proven executable -- a glibc build that starts fine but dies with SIGSYS on the decode
    // path passed the old check and failed the real build.
    const val ASSET_UNZBM_PROBE = "unzbm-probe.zip"
    const val PROBE_DIR_REL = "probe"

    // Python 3.14 on Android defaults to the "forkserver" start method, which is unavailable
    // there; the bundled mkapfs.py forces the "fork" context itself, and the worker count is
    // capped (a phone has no desktop's worth of cores to give to zlib).
    const val PY_CPU_COUNT = "4"

    // ---- Public storage (permanent firmware + image location) ---------------------------------
    // The user's requirement: the IPSW lives permanently at Download/hackios/ipsw and the built
    // images are published at Download/hackios/images, so they survive app clears and are
    // visible to a file manager. Both need MANAGE_EXTERNAL_STORAGE on Android 11+; without it
    // the app falls back to its own external directory (getExternalFilesDir).
    const val PUBLIC_HACKIOS_DIR = "hackios"
    const val PUBLIC_IPSW_SUBDIR = "ipsw"
    const val PUBLIC_IMAGES_SUBDIR = "images"

    // Names of the iOS images produced by the host prepare script (hackios-host-prepare.sh).
    const val IMG_SYSTEM = "system.apfs"
    const val IMG_ROOT = "root.apfs"

    // ---- root.apfs freshness ---------------------------------------------------------------
    // system.apfs is a copy of Apple's read-only root filesystem and only changes with the IPSW.
    // root.apfs is *ours*: it carries /redwood/bin/*, the redwood libraries, /redwood/etc/rc.jobs
    // and the LaunchDaemons. Every change to those needs root.apfs rebuilt and re-published, but
    // the two images live in public storage and survive app updates -- so an APK that ships new
    // scripts boots the *old* image unless the user happens to press the build button again.
    //
    // That failure mode is silent and looks exactly like "the change did nothing": the guest boots
    // fine, runs the stale rc.jobs, and the new test never appears in serial.log. Bump this number
    // whenever anything under userland/ (rc.jobs, the LaunchDaemons, src/) changes; the app then
    // refuses to start on a stale image and points at the build button instead.
    //
    // 0.17.0 (rev 3) is the first image whose "/" is 0755. Everything about the render path that can
    // be measured is measured and fine -- surf_test 80/0, the framebuffer negotiated at 1125x2436 --
    // and what is left is a permission: on Android the build runs with umask 0077, so every
    // directory it makes comes out 0700, and system.manifest cannot carry the volume's own root
    // (it lists paths *inside* the volume). So the system image's "/" was 0700, and SpringBoard --
    // the first thing it does is open("/") -- died with EACCES. mkapfs.py now states the root mode
    // itself instead of inheriting it, and build.sh sets umask 022 for the rest of the image.
    // 0.26.3：提升 3→4，强制丢弃用带 bug 的 mkjobs.py（RWA_PACKED16 变量覆盖）构建的旧缓存镜像。
    // 旧 root.apfs 里 /redwood/LaunchDaemons/RWA_PACKED16 是非法文件、真正的按需守护进程 plist 全缺失，
    // 导致 lsd 反复查不到 mobileactivationd 而「卡死」。提升 REV 后已发布的旧镜像被拒绝、用修复版脚本重建。
    // 0.28.0：提升 4→5，FAST 作业集首次包含 com.apple.mobileactivationd（上游 one-shot-build 组件 2）。
    // 少了它，FAST=1 的精简镜像里 lsd 永远查不到该服务，per-job 重试会把串口刷成风暴并拖慢启动。
    // 0.28.1：提升 5→6，补 com.apple.securityd + com.apple.SystemConfiguration.configd。
    // 真机日志（2026-11-05 10:44）显示 mobileactivationd 已注册，但查 securityd 得到 No such
    // process 后以 SIGTRAP 崩溃，12 次连崩；wifid 也在查 securityd。补上后才算真正接通激活链。
    // 0.28.2：提升 6→7，再补 com.apple.timed（提供 com.apple.timed.xpc）。同一崩溃序列里
    // mobileactivationd 先查的就是它，也回 No such process；一次补齐 securityd + timed，
    // 避免重编一次镜像后又因 timed 复现同类 SIGTRAP 连崩。
    // 0.29.1：提升 7→8。真机日志显示 securityd/wifid/powerd 仍在查
    // com.apple.SystemConfiguration.configd 并失败——那是 MachService 名，LaunchDaemon 的
    // label 其实是 com.apple.configd；把 job 名改对后才会真正生成 plist 并注册该服务。
    // 0.29.4：提升 8→9。kernel 已把触摸设备建成 /dev/hakios-touch，而 prebuilt 的
    // rwtouch 与 libredwood.dylib 仍硬编码 /dev/redwood-touch（真机日志里两行
    // "No such file or directory" 就是它），自动上滑与触摸库因此失效。两个预构件的字符串
    // 已等长改为 /dev/hakios-touch（空字节结尾），必须重建 root.apfs 才会生效；system.apfs 不动。
    const val IMAGE_REV = 9
    const val IMAGE_REV_FILE = ".image-rev"
}
