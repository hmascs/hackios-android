package com.android.gudao.iphoneos

import android.content.Context
import android.util.Log
import com.android.gudao.iphoneos.engine.SevenZipProgress
import com.android.gudao.iphoneos.engine.TailText
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Builds the iOS 16.5 system images **on the phone**, with the HackiOS project's own scripts and
 * a bundled Termux aarch64 toolchain. No host, no adb, no prebuilt tarball:
 *
 *  1. the three disk images are read straight out of the IPSW that lives in
 *     Download/hackios/ipsw (java.util.zip, random access: 7.46 GB instead of the whole 6.17 GB
 *     archive being streamed), and laid out where ios165/fetch.sh expects them;
 *  2. `ios165/fetch.sh` lists/extracts them with the bundled 7-Zip and finishes every LZBITMAP
 *     file with the cross-compiled unzbm (rootfs + OS cryptex + App cryptex + dyld cache);
 *  3. `userland/build.sh` (FAST=1 PREBUILT=1 GL=1) assembles the tree and packs it with
 *     tools/apfs/mkapfs.py -- the very script the host build uses. 0.25.0 switched from
 *     REST=1 (the ~490 on-demand plist set + three log/telemetry daemons) to FAST=1
 *     (19 desktop jobs, QUIET=1, no `cpu_test 240 &` busy loop), which is what removes
 *     the guest-side CPU spin and lookup storms seen on the HONOR 90 boot logs;
 *  4. system.apfs and root.apfs are published to Download/hackios/images and handed back for
 *     QEMU to boot; the ~9 GB of intermediates are then deleted.
 *
 * Everything runs in the app's private storage (which allows the hard links build.sh makes), so
 * the phone needs roughly 12 GB free there while it works, plus 2.6 GB in the public images
 * directory. The build is interruptible and every step reports progress.
 */
class ImageBuilder(private val context: Context) {

    interface Progress {
        /** A coarse phase label, e.g. "解包 rootfs (4.7 GB)". */
        fun onPhase(phase: String)
        /** Progress inside the current phase. */
        fun onProgress(done: Long, total: Long, label: String)
        /** A line of build output (also the place to notice something went wrong). */
        fun onLog(line: String)
    }

    /** Raised for every failure that should be shown to the user. */
    class BuildError(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val scriptsDir = File(context.filesDir, "run/${Constants.SCRIPTS_DIR_REL}")
    private val toolchainDir = File(context.filesDir, "run/${Constants.TOOLCHAIN_DIR_REL}")
    private val probeDir = File(context.filesDir, "run/${Constants.PROBE_DIR_REL}")
    private val ios165Dir = File(scriptsDir, "ios165")
    private val userlandDir = File(scriptsDir, "userland")

    @Volatile private var current: Process? = null

    val isRunning: Boolean get() = current != null

    /** Abort a running build. destroyForcibly() only reaps bash itself, so the tree is walked
     * through /proc and the 7z / python children are killed too -- otherwise they would keep
     * compressing for minutes with nobody reading their output. */
    fun cancel() {
        val p = current ?: return
        val pid = currentPid ?: -1L
        p.destroyForcibly()
        if (pid > 0) killTree(pid)
        current = null
        currentPid = -1L
        // The stage marker is written only on success, so a cancelled build resumes at this stage
        // instead of at the beginning.
    }

    /** Human-readable stage of the running build, for the UI progress card. */
    @Volatile var stage: String = "idle"
        private set

    /** The pid of the bash that runs the build, as written by the wrapper below. */
    @Volatile private var currentPid: Long = -1L

    /** SIGKILL [pid] and every descendant that is still ours (same uid). */
    private fun killTree(pid: Long) {
        val children = HashMap<Long, MutableList<Long>>()
        val myUid = android.os.Process.myUid()
        File("/proc").listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }?.forEach { d ->
            try {
                val stat = File(d, "stat").readText()
                // pid (comm) state ppid ... -- comm may contain spaces, so split after ')'
                val parts = stat.substringAfter(')').trim().split(Regex("\\s+"))
                val ppid = parts[1].toLong()
                val owner = File(d, "status").readLines()
                    .firstOrNull { it.startsWith("Uid:") }?.removePrefix("Uid:")?.trim()
                    ?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
                if (owner == myUid) children.getOrPut(ppid) { mutableListOf() }.add(d.name.toLong())
            } catch (_: Throwable) {
            }
        }
        fun killRecursively(p: Long) {
            children[p]?.forEach { killRecursively(it) }
            try {
                android.os.Process.killProcess(p.toInt())
            } catch (_: Throwable) {
            }
        }
        killRecursively(pid)
    }

    // ---- entry point --------------------------------------------------------------------------

    /**
     * @param ipsw the firmware, already downloaded and SHA-256 verified
     * @param publishDir where the two images are copied to (null: keep them in the build tree)
     * @return the published images (system.apfs, root.apfs)
     */
    fun build(ipsw: File, publishDir: File?, progress: Progress): Pair<File, File> {
        preflight(ipsw)
        stage = "preflight"
        // Resume at stage granularity. A 20–40 minute build that was cancelled (or ran out of
        // battery) used to start again from "extract 7.4 GB out of the IPSW" even though the DMGs
        // were already on disk. Each stage writes its marker only after it finished cleanly, so a
        // retry skips exactly the work that is already done.
        // A marker says a stage finished, not that what it produced is still on disk. The failure
        // card tells the user to delete ios165/rootfs when the extraction came out damaged, and
        // without this the fetch marker would send the retry straight to build.sh -- which then
        // dies on a tree that is not there, one stage too late to say anything useful.
        if (stageDone(STAGE_EXTRACT) && !File(ios165Dir, "rootfs.dmg").isFile) {
            progress.onLog("ios165/rootfs.dmg 不在了：extract 与 fetch 的阶段标记作废，重新开始")
            checkpoint.forget(STAGE_EXTRACT)
            checkpoint.forget(STAGE_FETCH)     // everything fetch reads came out of that extraction
        } else if (stageDone(STAGE_FETCH) && !File(ios165Dir, "rootfs").isDirectory) {
            progress.onLog("ios165/rootfs 不在了：重新解包（fetch 阶段标记作废）")
            checkpoint.forget(STAGE_FETCH)
        }
        if (!stageDone(STAGE_EXTRACT)) {
            stage = STAGE_EXTRACT
            progress.onLog("stage: extract")
            extractMembers(ipsw, progress)
            markStage(STAGE_EXTRACT)
        } else {
            progress.onLog("stage: extract — 已完成，跳过")
        }
        if (!stageDone(STAGE_FETCH)) {
            stage = STAGE_FETCH
            progress.onLog("stage: fetch")
            runFetch(progress)
            markStage(STAGE_FETCH)
        } else {
            progress.onLog("stage: fetch — 已完成，跳过")
        }
        if (!stageDone(STAGE_BUILD)) {
            stage = STAGE_BUILD
            progress.onLog("stage: build")
            runBuild(progress)
            markStage(STAGE_BUILD)
        } else {
            progress.onLog("stage: build — 已完成，跳过")
        }
        stage = "publish"
        val images = collect(publishDir, progress)
        cleanup()
        stage = "done"
        return images
    }

    private val checkpoint by lazy {
        com.android.gudao.iphoneos.engine.BuildCheckpoint(File(context.filesDir, "run/.build-stage"))
    }

    private fun stageDone(stage: String): Boolean = checkpoint.done(stage)

    private fun markStage(stage: String) = checkpoint.mark(stage)

    // ---- self test -----------------------------------------------------------------------------

    /** One self-test result. */
    data class Check(val name: String, val ok: Boolean, val detail: String = "")

    /**
     * Run every piece of the on-device toolchain the way the build will use it, and report.
     * Takes seconds, not minutes: the point is to fail *before* the user has waited 40 minutes
     * for a build that was doomed by a missing tool or an unwritable private directory.
     *
     * The heavy hitters are the two that only ever broke on Android: the posix-semaphore prefix
     * (CPython needs a `sem.` directory in the CWD) and the process pool that packs system.apfs.
     */
    fun verify(onCheck: (String) -> Unit = {}): List<Check> {
        val out = mutableListOf<Check>()
        fun add(name: String, ok: Boolean, detail: String = "") {
            out.add(Check(name, ok, detail))
            onCheck("${if (ok) "✓" else "✗"} $name${if (detail.isNotEmpty()) " — $detail" else ""}")
        }

        // ---- can anything run at all? ---------------------------------------------------------
        // Android 10 (API 29) removed the execute permission for the app home directory. The
        // SELinux domain is derived from targetSdkVersion, so an app targeting 26..28 keeps
        // executing what it unpacked while 29+ gets EACCES on every execve. That single policy is
        // what turns all of the runtime probes below into "error=13 Permission Denied", so it is
        // reported first and in those words -- otherwise it reads like nine broken tools.
        val deviceApi = android.os.Build.VERSION.SDK_INT
        val appTarget = context.applicationInfo.targetSdkVersion
        val execAllowed = appTarget <= 28 || deviceApi < 29
        add("应用私有目录可执行（Android 10+ 策略）", execAllowed,
            if (execAllowed) "targetSdk=$appTarget · 设备 API=$deviceApi · SELinux 域 ${selinuxDomain()}"
            else "targetSdk=$appTarget 且设备 API=$deviceApi：系统禁止在应用私有目录执行任何文件，" +
                "bash/python3/7zz 必然 error=13。需要 targetSdk≤28 的版本（本包应为 28）")

        // ---- layout -------------------------------------------------------------------------
        val need = listOf(
            "ios165/fetch.sh", "ios165/fetch-from-ipsw.py", "ios165/select-system.py",
            "ios165/mkvarskel.py", "tools/apfs/mkapfs.py", "tools/font_trim.py",
            "userland/build.sh", "userland/mkjobs.py", "userland/jobinfo.py",
            "xnu-deps/host/bin/unzbm")
        val missing = need.filter { !File(scriptsDir, it).exists() }
        add("构建脚本齐全（${need.size} 个）", missing.isEmpty(),
            if (missing.isEmpty()) "ios165/ + userland/ + tools/ + xnu-deps/"
            else "缺少 ${missing.joinToString()}，请先「部署预构件」")

        val tcBin = File(toolchainDir, "bin")
        val tools = listOf("bash", "python3", "python3.14", "7zz", "unzbm", "cmp", "find", "sed", "cp")
        val missingTools = tools.filter { !File(tcBin, it).exists() }
        add("工具链二进制（${tools.size} 个）", missingTools.isEmpty(),
            if (missingTools.isEmpty()) "7zz 26.04 / python 3.14 / bash 5.3 / coreutils / findutils / diffutils"
            else "缺少 ${missingTools.joinToString()}")

        // Existing is not the same as runnable: a zip carries no permission bits, so the deploy
        // step has to chmod each binary itself. This is the one check that tells the two
        // EACCES sources apart -- a missing exec bit (fixable by redeploying) versus the
        // Android 10+ policy above (fixable only by targetSdk).
        val needX = listOf("bash", "python3.14", "7zz", "cmp", "sed", "cp", "find")
        val noX = needX.filter { !File(tcBin, it).canExecute() }
        add("可执行位已设置（bin/ 0755）", noX.isEmpty(),
            if (noX.isEmpty()) "${needX.size} 个二进制均可执行 · 解包后已补 chmod"
            else "${noX.joinToString()} 缺少 exec 位 · zip 不保存权限位，重新「部署预构件」即可补上")

        // the 86 multi-call links Java's zip reader cannot restore
        val manifest = File(toolchainDir, "_symlinks.txt")
        val declared = if (manifest.exists()) manifest.readLines().count { it.contains('\t') } else 0
        val broken = if (manifest.exists()) manifest.readLines().mapNotNull { line ->
            val (tgt, rel) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val f = File(toolchainDir, rel)
            if (!f.exists()) rel else null
        } else listOf("<no manifest>")
        add("符号链接已重建", broken.isEmpty() && declared > 0,
            "$declared 个声明" + if (broken.isEmpty()) "，全部可解析" else "，失效：${broken.take(5)}")

        val free = context.filesDir.usableSpace
        // Hard links decide how much private storage the build peaks at. Android's SELinux policy
        // denies link(2) on app_data_file, so on a real phone build.sh's `hl` copies the extracted
        // trees into sysroot instead of linking them -- about 5 GB more at the peak. Probe the same
        // way build.sh does (one touch + one ln) and size the requirement from the answer.
        val hardLinks = canHardLink()
        val needGb = if (hardLinks) 12L else 16L
        add(if (hardLinks) "私有空间 ≥ 12 GB" else "私有空间 ≥ 16 GB（不能硬链接，需复制）",
            free >= (needGb shl 30),
            "可用 ${free / (1 shl 20)} MB" +
                if (hardLinks) "（峰值约 12 GB）"
                else "；本机 SELinux 不允许对应用私有目录 link(2)，build.sh 会改用复制，峰值约 16 GB")

        val ipsw = StoragePaths.ipswFile(context)
        add("固件已就位", ipsw.exists() && ipsw.length() == Constants.IPSW_SIZE_BYTES,
            if (ipsw.exists()) "${ipsw.length() / (1 shl 30)} GB · ${ipsw.name}" else "尚未下载")

        // ---- the executables actually run ----------------------------------------------------
        // Every probe goes through the same env ImageBuilder injects for the real build.
        val env = buildEnv()

        fun probe(cmd: List<String>, dir: File, seconds: Int = 60): Pair<Int, String> =
            runProbe(cmd, dir, env, seconds)

        // Every probe addresses the binary by absolute path instead of relying on PATH. With a
        // bare name the kernel's search falls through to /system/bin on EACCES, so a broken
        // toolchain/bin/cmp would silently be "verified" by toybox's cmp -- which is exactly the
        // false pass this self-test must never produce.
        fun tool(name: String) = File(tcBin, name).absolutePath

        val (rc, v) = probe(listOf(tool("bash"), "--version"), toolchainDir)
        add("bash 可执行", rc == 0, v.lineSequence().firstOrNull()?.take(60) ?: "rc=$rc")

        // fetch.sh and build.sh call python3 / 7zz / cp / sed / find / cmp / unzbm by bare name,
        // so $PATH must resolve every one of them into toolchain/bin before /system/bin gets a
        // look: toybox ships its own cmp, sed and find, and their different behaviour is enough to
        // quietly corrupt a build. bash reads $PATH from the env map we inject (unlike the kernel
        // lookup used to start bash itself, which sees only the app's own PATH), so this is the
        // level where the injected PATH actually matters -- and the level that used to be untested.
        val tcBinPath = File(toolchainDir, "bin").absolutePath
        val (rcPath, vPath) = probe(listOf(tool("bash"), "-c",
            "for t in python3 7zz cp sed find cmp unzbm mkdir rm chmod;\n" +
            "do p=\$(command -v \$t) || { echo \"MISS \$t\"; exit 1; };\n" +
            "case \$p in $tcBinPath/*) ;; *) echo \"WRONG \$t -> \$p\"; exit 1;; esac; done;\n" +
            "echo 'PATH ok: 11 tools resolve into the bundled toolchain'"), toolchainDir)
        add("脚本内 PATH 解析（不落到 toybox）", rcPath == 0,
            if (rcPath == 0) vPath.trim().lines().last()
            else vPath.trim().lines().lastOrNull { it.isNotBlank() }?.take(120) ?: "rc=$rcPath")

        val (rcPy, vPy) = probe(listOf(tool("python3"), "--version"), toolchainDir)
        add("python3 可执行", rcPy == 0, vPy.trim().ifEmpty { "rc=$rcPy" })

        val (rcF, vF) = probe(listOf(tool("python3"), "-c",
            "import multiprocessing as mp, lzma, zlib, hashlib, concurrent.futures as cf, struct, plistlib\n" +
            "ctx = mp.get_context('fork')\n" +
            "assert ctx.Process is not None\n" +
            "print('fork', ctx.get_start_method())"), toolchainDir)
        add("fork 多进程上下文 + 标准库", rcF == 0,
            if (rcF == 0) vF.trim().lines().last() else vF.trim().lines().lastOrNull()?.take(120) ?: "rc=$rcF")

        // SemLock is opened through a "./././..."-style name, and the Termux bionic patch writes
        // that name as a file inside the CWD -- historically only accepted when a directory called
        // "sem." exists there. Newer shims fall back to /dev/shm and no longer need it, but making
        // one costs nothing and keeps older devices working.
        val semDir = File(context.filesDir, "run/semtest").apply { mkdirs() }
        File(semDir, "sem.").mkdirs()
        val (rcS, vS) = probe(listOf(tool("python3"), "-c",
            "import multiprocessing as mp\n" +
            "ctx = mp.get_context('fork')\n" +
            "s = ctx.Semaphore(1); s.acquire()\n" +
            "assert s.acquire(False) is False\n" +
            "s.release()\n" +
            "q = ctx.Queue(); q.put(1); assert q.get() == 1\n" +
            "print('semaphore+queue ok')"), semDir)
        add("posix 信号量与队列", rcS == 0,
            if (rcS == 0) vS.trim().lines().last() else vS.trim().lines().lastOrNull()?.take(120) ?: "rc=$rcS")
        semDir.deleteRecursively()

        // The task function MUST be a module-level def, never a lambda. Tasks travel to the
        // workers through a pickle queue even under the fork start method, and pickle can only
        // rebuild a function by looking up its qualified name -- `__main__.sq` resolves, a lambda
        // does not (PicklingError: "Can't pickle <function <lambda>>", which the traceback wraps
        // in "when serializing concurrent.futures.process._CallItem object"). mkapfs.py submits
        // `compressed_group`, a module-level function, so this mirrors the real build.
        val (rcP, vP) = probe(listOf(tool("python3"), "-c",
            "import concurrent.futures as cf, multiprocessing as mp\n" +
            "def sq(i):\n" +
            "    return i * i\n" +
            "ctx = mp.get_context('fork')\n" +
            "with cf.ProcessPoolExecutor(max_workers=2, mp_context=ctx) as ex:\n" +
            "    r = list(ex.map(sq, range(8)))\n" +
            "assert r == [i * i for i in range(8)], r\n" +
            "print('pool map ok:', r)"), toolchainDir, 120)
        add("ProcessPoolExecutor 进程池", rcP == 0,
            if (rcP == 0) vP.trim().lines().last().take(80)
            else vP.lineSequence().firstOrNull { "Error" in it || "Exception" in it }
                ?.trim()?.take(130)
                ?: vP.lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.take(130) ?: "rc=$rcP")

        // A pool that can square numbers is not the same as mkapfs.py packing a volume: the real
        // packer submits a module-level `compressed_group` from a *script* (so `__main__` has to
        // survive the fork), batches 16 files per task, and runs zlib inside the workers. Pack a
        // throwaway volume with the shipped script and every one of those steps is exercised at
        // once -- in a couple of seconds, versus discovering it 30 minutes into a system.apfs run.
        val apfsProbe = File(context.filesDir, "run/probe-apfs")
        apfsProbe.deleteRecursively()
        val apfsSrc = File(apfsProbe, "src")
        File(apfsSrc, "sub").mkdirs()
        File(apfsProbe, "sem.").mkdirs()            // same CWD shape build.sh gives mkapfs
        for (i in 0 until 20) {
            val dir = if (i % 2 == 0) apfsSrc else File(apfsSrc, "sub")
            File(dir, "f$i.txt").writeText(("probe line $i\n").repeat(300))
        }
        val apfsOut = File(apfsProbe, "vol.apfs")
        val (rcMk, vMk) = probe(listOf(
            tool("python3"), File(scriptsDir, "tools/apfs/mkapfs.py").absolutePath,
            "-z", "-o", apfsOut.absolutePath, "Vol=${apfsSrc.absolutePath}"), apfsProbe, 180)
        val mkOk = rcMk == 0 && apfsOut.length() > 0
        add("mkapfs.py 真实打包（fork 池 + zlib）", mkOk,
            if (mkOk) "20 个文件 → ${apfsOut.length() / 1024} KB APFS 镜像"
            else vMk.lineSequence().firstOrNull { "Error" in it || "Exception" in it }?.trim()?.take(130)
                ?: vMk.lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.take(130) ?: "rc=$rcMk")
        apfsProbe.deleteRecursively()

        val (rc7, v7) = probe(listOf(tool("7zz")), toolchainDir)
        add("7zz 独立版（无需外部 codec 库）", rc7 == 0 && "7-Zip" in v7,
            v7.lineSequence().firstOrNull { "7-Zip" in it }?.trim()?.take(70)
                ?: v7.lineSequence().firstOrNull()?.trim()?.take(90) ?: "rc=$rc7")

        // 7zz must actually read an Apple APFS/DMG container -- that is what fetch.sh asks of it
        val (rcApfs, vApfs) = probe(listOf(tool("7zz"), "i"), toolchainDir)
        add("7zz 可列出压缩格式", rcApfs == 0 && "APFS" in vApfs,
            if ("APFS" in vApfs) "格式表含 APFS" else "格式表中未见 APFS")

        // unzbm has to survive the decode path, not merely start: the glibc build used to run
        // fine with no arguments and then died with SIGSYS on the first block, which is exactly
        // how the real build failed. So feed it two genuine decmpfs + resource-fork pairs (one
        // single block, one spanning six) and check the finished files.
        val plistOut = File(probeDir, "version.plist")
        val daemonOut = File(probeDir, "webbookmarksd")
        val probePairs = AssetDeployer(context).restoreProbe() &&
                File(probeDir, "version.plist:com.apple.decmpfs").exists() &&
                File(probeDir, "webbookmarksd:com.apple.decmpfs").exists()
        if (plistOut.exists()) plistOut.delete()
        if (daemonOut.exists()) daemonOut.delete()
        if (probePairs) {
            val (rcZ, vZ) = probe(listOf(tool("unzbm"), "version.plist", "webbookmarksd"),
                probeDir, 180)
            val plistBytes = try { plistOut.readBytes() } catch (t: Throwable) { null }
            val daemonLen = daemonOut.length()
            val plistOk = plistBytes?.size == 533 &&
                    String(plistBytes, 0, 32, Charsets.UTF_8).contains("<?xml") &&
                    plistBytes.toString(Charsets.UTF_8).contains("BuildAliasOf")
            val daemonOk = daemonLen == 372608L
            val leftovers = probeDir.listFiles()?.count {
                it.name.contains(":com.apple.")
            } ?: 0
            add("unzbm 真实解压 LZBITMAP",
                rcZ == 0 && plistOk && daemonOk && leftovers == 0,
                if (rcZ != 0) vZ.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(110)
                    ?: "rc=$rcZ"
                else if (!plistOk) "单块样本结果不对（${plistBytes?.size ?: "无输出"} 字节）"
                else if (!daemonOk) "多块样本结果不对（$daemonLen 字节，应为 372608）"
                else if (leftovers != 0) "属性文件未删除（$leftovers 个残留）"
                else "2 个真实样本解出 533 + 372608 字节，块表/解压/属性清理全部正确")
        } else {
            add("unzbm 真实解压 LZBITMAP", false, "probe 样本未部署，请先「部署预构件」")
        }

        val (rcC, vC) = probe(listOf(tool("cmp"), "--version"), toolchainDir)
        add("cmp 可执行（build.sh 的 PREBUILT 惯用法）", rcC == 0, vC.trim().lines().firstOrNull()?.take(50) ?: "rc=$rcC")

        val (rcAst, vAst) = probe(listOf(tool("python3"), "-c",
            "import ast\n" +
            "for f in ['ios165/select-system.py','ios165/mkvarskel.py','ios165/fetch-from-ipsw.py',\n" +
            "          'tools/apfs/mkapfs.py','userland/mkjobs.py','userland/jobinfo.py','tools/font_trim.py']:\n" +
            "    ast.parse(open('../hackios/' + f).read())\n" +
            "print('7 scripts parse')"), File(scriptsDir.parentFile, "toolchain"))
        add("Python 脚本语法", rcAst == 0, vAst.trim().lines().lastOrNull()?.take(80) ?: "rc=$rcAst")

        val (rcBn, vBn) = probe(listOf(tool("bash"), "-n", "fetch.sh"), ios165Dir)
        add("fetch.sh 语法", rcBn == 0, vBn.trim().lines().lastOrNull()?.take(100) ?: "ok")

        val (rcBb, vBb) = probe(listOf(tool("bash"), "-n", "build.sh"), userlandDir)
        add("build.sh 语法", rcBb == 0, vBb.trim().lines().lastOrNull()?.take(100) ?: "ok")

        return out
    }

    /** The SELinux domain this process runs in, e.g. "untrusted_app_27". The Android 10 exec
     * policy is decided per domain (and the domain is chosen from targetSdkVersion), so printing
     * it separates "wrong exec bit" from "policy denial" without a logcat session. */
    private fun selinuxDomain(): String = try {
        val raw = File("/proc/self/attr/current").readText().trim()
        raw.split(':').getOrNull(2) ?: raw
    } catch (t: Throwable) {
        "未知"
    }

    /** Can this device hard-link files inside the app's private directory?
     *
     * Android's SELinux policy has no `link` permission for app_data_file, so link(2) fails with
     * EACCES here -- the exact reason `cp -al` dies mid-tree and build.sh has to copy instead.
     * Probed the same way build.sh's `hl` does: one file, one link. Used to size the free-space
     * requirement (12 GB when linking works, 16 GB when it does not). */
    private fun canHardLink(): Boolean {
        val dir = File(context.filesDir, "run").apply { mkdirs() }
        val bash = File(toolchainDir, "bin/bash")
        if (!bash.canExecute()) return false
        // exactly what build.sh's hl() runs, in the same environment
        val (rc, _) = runProbe(
            listOf(bash.absolutePath, "-c",
                "rm -f .link-probe .link-probe.l && : > .link-probe && ln .link-probe .link-probe.l"),
            dir, buildEnv(), 30)
        return rc == 0
    }

    /** The environment ImageBuilder injects into every build step. */
    private fun buildEnv(): Map<String, String> {
        val tc = toolchainDir
        return mapOf(
            "PATH" to "${File(tc, "bin").absolutePath}:/system/bin",
            "LD_LIBRARY_PATH" to File(tc, "lib").absolutePath,
            "PYTHONHOME" to File(tc, "usr").absolutePath,
            "PYTHON_CPU_COUNT" to Constants.PY_CPU_COUNT)
    }

    // ---- steps --------------------------------------------------------------------------------

    private fun preflight(ipsw: File) {
        if (!ipsw.exists()) throw BuildError(
            "固件不存在：${ipsw.absolutePath}\n请先在第 2 步下载 iOS 16.5 固件")
        if (ipsw.length() != Constants.IPSW_SIZE_BYTES) throw BuildError(
            "固件大小不符：${ipsw.length()} ≠ ${Constants.IPSW_SIZE_BYTES}\n" +
                "文件可能下载不完整，请删除后重新下载")
        // A truncated/corrupted download can still report the right size but carry a bad zip
        // signature, which would otherwise blow up deep inside extractMembers as the cryptic
        // "invalid LOC header (bad signature)". Catch it here with a clear, actionable message.
        if (!isZipFile(ipsw)) throw BuildError(
            "固件文件损坏：ZIP 头部签名无效（不是合法的 .ipsw 压缩包）\n" +
                "文件很可能下载或传输时被截断。请删除后重新下载；\n" +
                "若反复失败，建议改用电脑下载并 sha256sum 校验后 adb push 到 Download/hackios/ipsw/")
        val buildScript = File(userlandDir, "build.sh")
        val python = File(toolchainDir, "bin/python3.14")
        if (!buildScript.exists() || !python.exists()) throw BuildError(
            "构建脚本或工具链未部署：${buildScript.name}=${buildScript.exists()} " +
                "python3.14=${python.exists()}\n请先点「部署预构件」")
        // The build writes 7.46 GB of images out of the IPSW, unpacks ~9 GB more and packs 2.5 GB.
        // When hard links are unavailable (always, on Android: SELinux denies link(2) on
        // app_data_file) build.sh's hl() copies the extracted trees into sysroot instead of
        // linking them, which adds about 5 GB at the peak.
        val need = (if (canHardLink()) 12L else 16L) shl 30
        val free = context.filesDir.usableSpace
        if (free < need) throw BuildError(
            "私有存储不足：需要约 ${need / (1 shl 30)} GB，当前可用 ${free / (1 shl 30)} GB\n" +
                "请清理空间后重试（镜像构建在应用私有目录中进行，无法使用 SD 卡）")
        File(userlandDir, "sem.").mkdirs()   // the patched semaphore prefix lands in the CWD
        Log.i(TAG, "preflight ok: ${free / (1 shl 30)} GB free, ipsw ${ipsw.length() / (1 shl 30)} GB")
    }

    /**
     * True if [f] begins with the ZIP local-file-header magic (PK\x03\x04). A truncated or
     * non-zip file can still report the expected byte size, so we peek at the signature rather
     * than trust the length alone -- this is what turns "invalid LOC header" into a clear message.
     */
    private fun isZipFile(f: File): Boolean {
        if (f.length() < 4) return false
        val sig = ByteArray(4)
        try {
            java.io.FileInputStream(f).use { if (it.read(sig) != 4) return false }
        } catch (_: Exception) {
            return false
        }
        return sig[0] == 0x50.toByte() && sig[1] == 0x4B.toByte() &&
               sig[2] == 0x03.toByte() && sig[3] == 0x04.toByte()
    }

    /** Read the three disk images out of the IPSW zip and put them where fetch.sh looks. */
    private fun extractMembers(ipsw: File, progress: Progress) {
        progress.onPhase("解包 IPSW 镜像成员（${Constants.IPSW_EXTRACT_BYTES / (1 shl 30)} GB）")
        ios165Dir.mkdirs()
        var done = 0L
        try {
            ZipFile(ipsw).use { zip ->
            for ((member, localName, size) in Constants.IPSW_MEMBERS) {
                val entry = zip.getEntry(member) ?: throw BuildError(
                    "IPSW 中找不到成员 $member\n这个文件不是 iPhone X (iPhone10,3/iPhone10,6) 的 iOS 16.5 固件")
                val out = File(ios165Dir, localName)
                if (out.length() == size) {
                    done += size
                    progress.onProgress(done, Constants.IPSW_EXTRACT_BYTES, "$localName 已存在")
                    continue
                }
                if (out.exists()) out.delete()
                val part = File(out.parentFile, "$localName.part")
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(part).use { fos ->
                        val buf = ByteArray(8 shl 20)
                        var written = 0L
                        var lastReport = 0L
                        while (written < size) {
                            val want = minOf(buf.size.toLong(), size - written).toInt()
                            val n = input.read(buf, 0, want)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            written += n
                            done += n
                            // 32 MB between UI updates: a 4.7 GB member would otherwise post
                            // ~150 runOnUiThread runnables a second.
                            if (written - lastReport >= (32L shl 20) || written == size) {
                                lastReport = written
                                progress.onProgress(done, Constants.IPSW_EXTRACT_BYTES,
                                    "$localName ${written / (1 shl 20)} / ${size / (1 shl 20)} MB")
                            }
                        }
                        if (written != size) throw BuildError("$member 解包不完整：$written ≠ $size")
                    }
                }
                if (!part.renameTo(out)) throw BuildError("$localName 写入失败（无法改名）")
                progress.onLog("extract: $member -> $localName ($size bytes)")
            }
        }
        } catch (e: java.util.zip.ZipException) {
            throw BuildError(
                "固件文件损坏，无法解包 ZIP：${e.message}\n" +
                "请删除 IPSW 重新下载，或用电脑下载后 adb push 到 Download/hackios/ipsw/")
        }
    }

    /** ios165/fetch.sh: list, select, extract, unzbm. The DMGs are already in place, so it
     * downloads nothing; KEEP is deliberately not set, each image is deleted after its section. */
    private fun runFetch(progress: Progress) {
        progress.onPhase("fetch.sh：解包 rootfs / OS cryptex / App cryptex")
        runProcess(
            cmd = listOf("fetch.sh"),
            dir = ios165Dir,
            progress = progress,
            phaseFor = { line ->
                when {
                    "listing the image" in line -> "读取 rootfs 目录结构（4.7 GB，无进度输出）"
                    line.startsWith("fetch.sh: extracting ") && line.endsWith("files") ->
                        "解包 rootfs 文件（4.7 GB）"
                    "extracting the cryptex" in line -> "解包 OS cryptex 与 dyld 共享缓存（2.7 GB）"
                    "extracting the App cryptex" in line -> "解包 App cryptex"
                    "fetch.sh: done" in line -> "fetch.sh 完成"
                    else -> null
                }
            })
        for (d in listOf("rootfs", "cryptex", "cryptex-app")) {
            if (!File(ios165Dir, d).isDirectory) throw BuildError("fetch.sh 未生成 $d/")
        }
        if (!File(ios165Dir, "20F66__iPhone10,3_6/dyld_shared_cache_arm64").exists()) {
            throw BuildError("fetch.sh 未生成 dyld 共享缓存")
        }
        // The cryptex has no manifest: its files' modes are whatever 7z wrote, under the app's
        // umask of 0077 -- which used to make every extracted file 0600. build.sh now states the
        // modes itself, so this is not fatal, but it says the extraction ran without the umask
        // fix (an old scripts tree, or a 7z that masks the mode itself), and the log is the only
        // place that would ever show it: a 0600 libobjc-trampolines.dylib in the image is what
        // made SpringBoard die with "open() failed with errno=13" -- a correct graft, a black screen.
        val probe = File(ios165Dir, "cryptex/usr/lib/libobjc-trampolines.dylib")
        if (probe.exists()) {
            val world = java.nio.file.Files.getPosixFilePermissions(probe.toPath())
                .contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ)
            if (!world) {
                progress.onLog("注意：cryptex 提取的文件不是全局可读（umask 0077 所致），" +
                    "build.sh 会强制改回 0755/0644；若此提示反复出现，请重新「部署预构件」")
            }
        }
    }

    /** userland/build.sh: mkjobs + mkapfs, the same recipe the host build runs. */
    private fun runBuild(progress: Progress) {
        progress.onPhase("build.sh：生成 LaunchDaemons 与 APFS 镜像")
        // SYSTEM=1 does not mean "build the system image", it means "build it even though nothing
        // about it changed": build.sh rebuilds system.apfs on its own when the file is missing or
        // when stage0, the manifest or a cryptex is newer than it. Passing it every time therefore
        // cost 2.5 GB of zlib and a full re-publish of the resident disk image for a change that
        // lives in root.apfs alone -- and root.apfs (our init, the redwood libraries, rc.jobs, the
        // LaunchDaemons) is rebuilt unconditionally on every run.
        //
        // But there is one input build.sh cannot see: the mode of the volume's own root. It is not
        // in system.manifest (that lists paths inside the volume), it is taken from the staging
        // directory's st_mode, and it was 0700 for as long as this build ran on Android with its
        // umask of 0077 -- which is why SpringBoard, whose first act is to open("/"), died with
        // EACCES. A change in mkapfs.py changes system.apfs and nothing about the timestamps
        // build.sh looks at, so the dependency is made explicit here: if the packer is newer than
        // the image it produced, ask for the system image to be rebuilt.
        val sysImage = File(userlandDir, "system.apfs")
        val packer = File(scriptsDir, "tools/apfs/mkapfs.py")
        val stalePacker = packer.exists() &&
            (!sysImage.exists() || packer.lastModified() > sysImage.lastModified())
        runProcess(
            cmd = listOf("build.sh"),
            dir = userlandDir,
            progress = progress,
            // GL=1 bakes the OpenGL ES runtime (libOSMesa.8.dylib, libredwood-gl.dylib and the
            // gl_test / u2f_test probes, all carried in hackios-scripts.zip) into root.apfs -- the
            // same step the host GL=1 build takes. Without it the gl=1 boot-arg has no library to
            // load and the window server falls back to software (no GL) rendering.
            // 0.25.0：REST=1 会把 iOS 全部 ~490 个按需守护进程 plist 塞进 root.apfs，
            // 真机日志里表现为 `programs 237 -> 275`、analyticsd/lsd 反复 failed lookup、
            // `process lsd denied attempt to escape CPU monitor` 刷屏 —— 全是 guest 侧的
            // CPU 空转。FAST=1 换成 16 个桌面必需作业 + QUIET=1（launchd stderr 不再刷屏）
            // + rc.jobs.fast（去掉 `cpu_test 240 &` 四分钟后台压测）。
            env = mapOf("FAST" to "1", "PREBUILT" to "1", "GL" to "1") +
                if (stalePacker) mapOf("SYSTEM" to "1") else emptyMap(),
            phaseFor = { line ->
                when {
                    "not compiling" in line -> "使用预编译用户态程序"
                    "mkapfs.py: root.apfs" in line -> "打包 root.apfs"
                    "mkapfs.py: system.apfs" in line -> "打包 system.apfs（2.5 GB，最久的一步）"
                    // Was `"jobs" in line`: any line mentioning rc.jobs flipped the phase and
                    // reset the clock, so the card jumped around while nothing restarted.
                    "mkjobs.py:" in line -> "生成 LaunchDaemons"
                    else -> null
                }
            })
        val sys = File(userlandDir, "system.apfs")
        val rt = File(userlandDir, "root.apfs")
        if (!sys.exists() || !rt.exists()) throw BuildError(
            "build.sh 未生成 system.apfs / root.apfs")
        progress.onLog("built: system.apfs=${sys.length()} root.apfs=${rt.length()}")
    }

    /** Copy the images to the public directory (the user's permanent location) and return them. */
    private fun collect(publishDir: File?, progress: Progress): Pair<File, File> {
        val sys = File(userlandDir, "system.apfs")
        val rt = File(userlandDir, "root.apfs")
        if (publishDir == null) return sys to rt
        publishDir.mkdirs()
        val free = publishDir.usableSpace
        val need = sys.length() + rt.length() + (512L shl 20)
        if (free < need) throw BuildError(
            "发布目录空间不足：$publishDir 需要 ${need / (1 shl 30)} GB，可用 ${free / (1 shl 30)} GB")
        progress.onPhase("发布镜像到 ${publishDir.absolutePath}")
        val targets = listOf(sys to File(publishDir, Constants.IMG_SYSTEM),
            rt to File(publishDir, Constants.IMG_ROOT))
        var done = 0L
        for ((src, dst) in targets) {
            // Length alone is not enough: a root.apfs rebuilt with a different rc.jobs can land
            // within a few bytes of the old one, and then the new image would be skipped and the
            // boot would keep reading the old copy in Download/hackios/images.
            if (dst.exists() && dst.length() == src.length() && dst.lastModified() >= src.lastModified()) {
                done += src.length()
                progress.onProgress(done, need, "${dst.name} 已存在")
                continue
            }
            val part = File(dst.parentFile, "${dst.name}.part")
            src.inputStream().use { input ->
                FileOutputStream(part).use { fos ->
                    val buf = ByteArray(8 shl 20)
                    var written = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        written += n
                        done += n
                        if (written - lastReport >= (32L shl 20)) {
                            lastReport = written
                            progress.onProgress(done, need, "${dst.name} ${written / (1 shl 20)} MB")
                        }
                    }
                }
            }
            if (!part.renameTo(dst)) throw BuildError("无法写入 ${dst.absolutePath}")
            progress.onLog("published: ${dst.absolutePath}")
        }
        // Stamp which revision of userland/ these images came from. Both .apfs files live in
        // public storage and outlive the app, so without this stamp an updated APK would keep
        // booting yesterday's rc.jobs with no visible symptom (see Constants.IMAGE_REV).
        File(publishDir, Constants.IMAGE_REV_FILE).writeText(Constants.IMAGE_REV.toString())
        return File(publishDir, Constants.IMG_SYSTEM) to File(publishDir, Constants.IMG_ROOT)
    }

    /**
     * Revision stamped on the images currently in public storage, or 0 when they carry no stamp.
     *
     * 0 means the images were built by an older build of this app, which is exactly the case that
     * has to be refused: those images predate the scripts shipped in this APK. Callers compare
     * against [Constants.IMAGE_REV] and force a rebuild on a mismatch.
     */
    fun publishedRev(): Int {
        val f = File(StoragePaths.imagesDir(context), Constants.IMAGE_REV_FILE)
        if (!f.exists()) return 0
        return f.readText().trim().toIntOrNull() ?: 0
    }

    /** Delete the ~9 GB of intermediates: the extraction, the DMGs and the staging trees. */
    private fun cleanup() {
        checkpoint.reset()
        var freed = 0L
        fun rm(f: File) {
            freed += f.length()
            f.deleteRecursively()
        }
        for (name in listOf("rootfs", "cryptex", "cryptex-app", "20F66__iPhone10,3_6",
            "rootfs.dmg", "cryptex-os.dmg", "cryptex-app.dmg",
            "rootfs.slt", "system.files", "system.manifest", "var.skel",
            ".done-rootfs", ".done-cryptex", ".done-cryptex-app")) {
            val f = File(ios165Dir, name)
            if (f.exists()) rm(f)
        }
        ios165Dir.listFiles { f -> f.name.startsWith("extract-") }?.forEach { rm(it) }
        for (name in listOf("sysroot", "root", "obj", "flashboot", "flashboot.os",
            "flashboot.boot.apfs", "flashboot.os.apfs", "flashboot.apfs", "hello")) {
            val f = File(userlandDir, name)
            if (f.exists()) rm(f)
        }
        Log.i(TAG, "cleanup: freed ${freed / (1 shl 20)} MB of intermediates")
    }

    // ---- process plumbing ---------------------------------------------------------------------

    /** Full logs land here so a failure 40 minutes in is still diagnosable. */
    private val logDir: File get() = File(context.filesDir, "run/build-logs").apply { mkdirs() }

    /**
     * Run a short command and capture its output. Used by [verify] and by nothing else: the
     * real build streams through [runProcess].
     */
    private fun runProbe(
        cmd: List<String>,
        dir: File,
        env: Map<String, String>,
        timeoutSeconds: Int
    ): Pair<Int, String> {
        val pb = ProcessBuilder(cmd).directory(dir)
        val e = pb.environment()
        e.putAll(env)
        // the build steps set these too; some tools insist on having them
        e["HOME"] = dir.absolutePath
        e["TMPDIR"] = dir.absolutePath
        pb.redirectErrorStream(true)
        val p = try {
            pb.start()
        } catch (t: Throwable) {
            // 126 = found but could not be executed (EACCES). 127 would mean "no such file", and
            // reporting that for a permission problem is what made the first device run look like
            // a missing 7zz. Spell the errno out -- error=13 is the Android 10+ exec policy.
            return 126 to (t.message ?: t.javaClass.simpleName)
                .replace("error=13", "EACCES/error=13 (Permission denied)")
                .replace("error=2", "ENOENT/error=2 (No such file)")
        }
        val sb = StringBuilder()
        try {
            val reader = p.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                if (sb.length < 64 * 1024) sb.append(line).append('\n')
            }
        } catch (_: Throwable) {
        }
        val done = p.waitFor(timeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
        if (!done) {
            p.destroyForcibly()
            return 124 to (sb.toString() + "\n[timed out after ${timeoutSeconds}s]")
        }
        return p.exitValue() to sb.toString()
    }

    private fun runProcess(
        cmd: List<String>,
        dir: File,
        progress: Progress,
        env: Map<String, String> = emptyMap(),
        phaseFor: (String) -> String?
    ) {
        // The wrapper writes its own pid ($$ survives the exec) so cancel() can kill the whole
        // tree -- java.lang.Process.pid() needs API 33, and this app supports 24.
        //
        // The shell is addressed by ABSOLUTE PATH, never by the name "bash". Android's
        // ProcessBuilder resolves a bare command name with execvp(), which searches the
        // *launching* process's PATH -- the zygote-inherited /product/bin:/apex/...:/system/bin
        // list -- and not the env map handed to the child (that map only becomes execve()'s envp,
        // long after the lookup happened). toolchain/bin is not on that list, so "bash" is never
        // found; and since some directory on the system PATH answers EACCES for an untrusted_app,
        // execvp() reports the remembered EACCES instead of ENOENT, which is why the failure reads
        // `Cannot run program "bash": error=13, Permission denied` -- a permission error for a file
        // that is 0755 and executable.
        // Inside the shell the problem disappears: bash resolves its own $PATH from the env map we
        // set, which starts with toolchain/bin, so build.sh's bare `python3` / `7zz` calls are fine.
        val shell = File(toolchainDir, "bin/bash").absolutePath
        val pidFile = File(dir, ".build-pid").apply { delete() }
        val script = "echo \$\$ > '" + pidFile.absolutePath + "'; exec '" + shell + "' \"\$@\""
        val wrapped = listOf(shell, "-c", script, "build", *cmd.toTypedArray())
        val pb = ProcessBuilder(wrapped).directory(dir)
        val e = pb.environment()
        e.putAll(buildEnv())
        e["HOME"] = dir.absolutePath
        e["TMPDIR"] = dir.absolutePath
        e.putAll(env)
        pb.redirectErrorStream(true)
        val logFile = File(logDir, "${cmd.joinToString("-").replace('/', '_')}-" +
            "${System.currentTimeMillis()}.log")
        val tail = ArrayDeque<String>(TAIL_LINES)          // what the error message carries
        val p = try {
            pb.start().also { current = it }
        } catch (t: Throwable) {
            throw BuildError("无法启动 bash ${cmd.joinToString(" ")}：${t.message}", t)
        }
        for (i in 0 until 40) {                     // wait for the wrapper to report its pid
            if (pidFile.exists()) break
            if (!p.isAlive) break
            Thread.sleep(50)
        }
        currentPid = try {
            if (pidFile.exists()) pidFile.readText().trim().toLong() else -1L
        } catch (_: Throwable) {
            -1L
        }
        try {
            val writer = logFile.bufferedWriter()
            var phaseLabel = ""
            var lastPercent = -1
            try {
                p.inputStream.bufferedReader().use { br ->
                    while (true) {
                        val line = br.readLine() ?: break
                        if (current == null) break            // cancelled
                        writer.write(line); writer.newLine()
                        if (tail.size >= TAIL_LINES) tail.removeFirst()
                        tail.addLast(line)
                        // 7z's progress, made readable by fetch.sh ("  12% 34 - path"). It is a
                        // single line refreshed 20 times a second, so it is progress, not log --
                        // and only a *changed* percentage is published: recomposing the card 20
                        // times a second behind a blurred glass panel is exactly the jank this
                        // app was fixed for.
                        val pct = SevenZipProgress.percent(line)
                        if (pct != null) {
                            if (pct != lastPercent) {
                                lastPercent = pct
                                progress.onProgress(pct.toLong(), 100L, "7z 解包")
                            }
                        } else {
                            progress.onLog(line)
                        }
                        phaseFor(line)?.let { phaseLabel = it; progress.onPhase(it) }
                    }
                }
            } finally {
                writer.flush(); writer.close()
            }
            val code = p.waitFor()
            if (current == null) throw BuildError("已取消")
            if (code != 0) {
                // "退出码 1" on its own is useless: the last lines are what say why. But so is a
                // *head-first* cut of those lines. The tail is forty lines and the reason is at
                // its END -- python's traceback, 7z's "No space left on device" -- so taking the
                // first 4000 characters kept forty lines of "job com.apple..." and threw the
                // traceback away. That is exactly the card this build shipped: "build.sh 失败
                // （退出码 1）" over a screenful of daemon names and nothing that said why.
                val joined = tail.joinToString("\n")
                // A python traceback ends with "SomeError: what went wrong" and a shell failure
                // with the command that refused; either belongs in the bold line, where a person
                // reads before deciding whether to scroll.
                val reason = TailText.reasonOf(tail, 140)
                throw BuildError(
                    "${cmd.joinToString(" ")} 失败（退出码 $code）" +
                        (if (reason.isEmpty()) "" else "：$reason") +
                        "\n\n最后 ${tail.size} 行输出（尾部优先）：\n" + TailText.of(joined, TAIL_CHARS) +
                        "\n\n完整日志：${logFile.absolutePath}")
            }
        } catch (t: BuildError) {
            p.destroyForcibly()
            throw t
        } finally {
            pidFile.delete()
            current = null
            currentPid = -1L
        }
    }

    companion object {
        private const val TAG = "ImageBuilder"

        private const val TAIL_LINES = 40

        /** Characters of the tail carried into the error card, from the END of it. */
        private const val TAIL_CHARS = 3000

        const val STAGE_EXTRACT = "extract"
        const val STAGE_FETCH = "fetch"
        const val STAGE_BUILD = "build"
    }
}
