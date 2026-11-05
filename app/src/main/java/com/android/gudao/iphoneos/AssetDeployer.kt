package com.android.gudao.iphoneos

import android.content.Context
import android.util.Log
import com.android.gudao.iphoneos.engine.AppLog
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipInputStream

/** Copies the bundled assets (XNU kernel, HackiOS loader, EDK2 UEFI firmware, the Termux qemu
 * bundle, and the two bundles the on-device image build needs) out of the APK into the
 * app-private working directory.
 *
 * Bundles and their layout after extraction (all under runDirectory()):
 *  - qemu/qemu-system-aarch64 + qemu/lib (the .so files)   qemu-android-bundle.zip
 *    (LD_LIBRARY_PATH=qemu/lib)
 *  - hackios/...                                 hackios-scripts.zip (build scripts, repo layout)
 *  - toolchain/bin, toolchain/lib, toolchain/usr  hackios-toolchain.zip (PYTHONHOME=toolchain/usr)
 *
 * The toolchain bundle carries its 86 symlinks (coreutils/7zz/python3.14 multi-call links) as a
 * plain `_symlinks.txt` manifest, because Java's zip reader cannot restore symlinks; they are
 * recreated here. Each archive is unpacked under the folder named by its caller, so a bundle may
 * either carry that top folder or not (qemu-android-bundle.zip does, the other two do not).
 * Extraction is skipped when a stamp file says the same asset was already unpacked, so a second
 * launch costs nothing.
 *
 * No Apple files are bundled. The kernel, loader and build scripts are HackiOS artifacts
 * released under APSL 2.0; the iOS files themselves come from the firmware the app downloads. */
class AssetDeployer(private val context: Context) {

    private val runDir: File get() = File(context.filesDir, "run")

    data class DeployResult(
        val kernel: Boolean,
        val loader: Boolean,
        val uefi: Boolean,
        val vars: Boolean,
        val qemu: Boolean,
        val scripts: Boolean,
        val toolchain: Boolean,
        val probe: Boolean,
        val missing: List<String>
    ) {
        val ok: Boolean get() = kernel && loader && uefi && vars && qemu && scripts && toolchain
    }

    fun deploy(): DeployResult {
        val run = runDir.apply { mkdirs() }
        val esp = File(run, "esp/EFI/BOOT").apply { mkdirs() }

        val kernel = copyAsset(Constants.ASSET_KERNEL, File(run, "kernel"))
        val loaderFile = File(run, "loader.efi")
        val loader = copyAsset(Constants.ASSET_LOADER, loaderFile) &&
                copyRaw(loaderFile, File(esp, "BOOTAA64.EFI"))   // ESP volume for UEFI
        val uefi = copyAsset(Constants.ASSET_UEFI, File(run, Constants.ASSET_UEFI))
        val vars = copyAsset(Constants.ASSET_VARS, File(run, Constants.ASSET_VARS))
        migrateLegacyLayout(run)
        val qemu = unzipBundle(
            Constants.ASSET_QEMU_BUNDLE, Constants.QEMU_DIR_REL, File(run, Constants.QEMU_BIN_REL)
        )
        val scripts = unzipBundle(
            Constants.ASSET_SCRIPTS_BUNDLE, Constants.SCRIPTS_DIR_REL, File(run, scriptsMarker())
        )
        val toolchain = unzipBundle(
            Constants.ASSET_TOOLCHAIN_BUNDLE, Constants.TOOLCHAIN_DIR_REL, File(run, toolchainBin())
        )
        // Two real decmpfs + resource-fork pairs, for the self-test's unzbm check. Not part of
        // the build: a missing probe only costs that one check, so it is not in DeployResult.ok.
        val probe = unzipBundle(
            Constants.ASSET_UNZBM_PROBE, Constants.PROBE_DIR_REL, File(run, probeFile())
        )

        val missing = buildList {
            if (!kernel) add(Constants.ASSET_KERNEL)
            if (!loader) add(Constants.ASSET_LOADER)
            if (!uefi) add(Constants.ASSET_UEFI)
            if (!vars) add(Constants.ASSET_VARS)
            if (!qemu) add(Constants.ASSET_QEMU_BUNDLE)
            if (!scripts) add(Constants.ASSET_SCRIPTS_BUNDLE)
            if (!toolchain) add(Constants.ASSET_TOOLCHAIN_BUNDLE)
            if (!probe) add(Constants.ASSET_UNZBM_PROBE)
        }
        Log.i(TAG, "deploy: kernel=$kernel loader=$loader uefi=$uefi vars=$vars qemu=$qemu " +
                "scripts=$scripts toolchain=$toolchain probe=$probe")
        return DeployResult(kernel, loader, uefi, vars, qemu, scripts, toolchain, probe, missing)
    }

    // ---- bundle extraction --------------------------------------------------------------------

    /** Extract an asset zip under `run/<destTop>/`. A stamp file records the layout revision and
     * the asset's size so an unchanged bundle is not unpacked again on every launch.
     *
     * The destination is passed in and the archive's own top folder is stripped when it happens to
     * be named the same. Both layouts therefore work: `hackios-scripts.zip` holds `ios165/…` at its
     * root while `qemu-android-bundle.zip` holds `qemu/…`. Deriving the target from the caller (and
     * not from the archive) is what keeps extraction and [probe] from drifting apart -- the earlier
     * version unpacked everything bare into the run directory, so `run/hackios/userland/build.sh`
     * never appeared and both build bundles were reported missing even though they sit in the APK.
     *
     * The stamp carries [LAYOUT_VERSION] as well as the size: permission bits and symlinks are
     * applied *after* extraction, so an APK that changes only that logic (same zip bytes) still
     * has to unpack once more, otherwise an updated app would keep running with the old, possibly
     * non-executable tree. */
    private fun unzipBundle(assetName: String, destTop: String, probe: File): Boolean {
        val destRoot = File(runDir, destTop).apply { mkdirs() }
        val stamp = File(runDir, ".stamp-${assetName}")
        val size = try {
            context.assets.openFd(assetName).use { it.length }
        } catch (t: Throwable) {
            -1L
        }
        val want = "$LAYOUT_VERSION:$size"
        if (probe.exists() && stamp.exists() && stamp.readText().trim() == want) {
            // Already unpacked -- but the permission bits and symlinks are applied after
            // extraction, so they are re-asserted here (cheap: ~800 chmods + 86 symlinks). A tree
            // unpacked by an older build, or one whose chmod silently failed, heals on the next
            // deploy instead of keeping every tool non-executable until the app is cleared.
            afterExtract(assetName)
            return true
        }
        return try {
            context.assets.open(assetName).use { asset ->
                ZipInputStream(asset).use { zis ->
                    var entry = zis.nextEntry
                    var written = 0
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val rel = entryRelative(entry.name, destTop)
                            val out = if (rel == null) null else File(destRoot, rel)
                            if (out == null) {
                                Log.w(TAG, "$assetName: skipping unsafe entry ${entry.name}")
                            } else {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                                written++
                            }
                        }
                        entry = zis.nextEntry
                    }
                    Log.i(TAG, "$assetName: $written files -> run/$destTop")
                }
            }
            if (!probe.exists()) {
                Log.w(TAG, "$assetName extracted but ${probe.name} not found")
                return false
            }
            afterExtract(assetName)
            stamp.writeText(want)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "$assetName missing or extraction failed: ${t.message}")
            false
        }
    }

    /** Re-unpack the unzbm sample bundle.
     *
     * The self-test *consumes* the sample: unzbm deletes the `:com.apple.decmpfs` and
     * `:com.apple.ResourceFork` pair it finishes and leaves the plain file behind. A second run
     * would therefore find no input, so the self-test calls this before every attempt. It is
     * ~100 KB out of the APK, so there is no reason to be clever about restoring only what is
     * missing. */
    fun restoreProbe(): Boolean {
        val dest = File(runDir, Constants.PROBE_DIR_REL).apply { mkdirs() }
        File(dest, "version.plist").delete()
        File(dest, "webbookmarksd").delete()
        return try {
            var written = 0
            context.assets.open(Constants.ASSET_UNZBM_PROBE).use { asset ->
                ZipInputStream(asset).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val rel = entryRelative(entry.name, Constants.PROBE_DIR_REL)
                            if (rel != null) {
                                File(dest, rel).apply { parentFile?.mkdirs() }
                                    .outputStream().use { fos -> zis.copyTo(fos) }
                                written++
                            } else {
                                Log.w(TAG, "probe: skipping unsafe entry ${entry.name}")
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            }
            written > 0
        } catch (t: Throwable) {
            Log.w(TAG, "probe restore failed: ${t.message}")
            false
        }
    }

    /** Map a zip entry to a path under the bundle's target folder: drop a leading folder named
     * [destTop] if the archive already has one, and refuse anything that escapes the target. */
    private fun entryRelative(name: String, destTop: String): String? {
        var rel = name.replace('\\', '/').trimStart('/')
        if (rel.startsWith("$destTop/")) rel = rel.substring(destTop.length + 1)
        if (rel.isEmpty()) return null
        if (rel.split('/').any { it == ".." }) return null
        return rel
    }

    /** Earlier builds unzipped both bundles straight into the run directory -- the archives carry no
     * top folder of their own -- so bin/, usr/, ios165/ … were left scattered there. Clear those
     * once, otherwise a re-deploy keeps ~27 MB of orphans next to the real trees. */
    private fun migrateLegacyLayout(run: File) {
        val marker = File(run, ".layout-3")
        if (marker.exists()) return
        listOf(
            "bin", "lib", "usr", "ios165", "userland", "tools", "prebuilt", "xnu-deps",
            "xnu-env.sh", "_symlinks.txt"
        ).forEach { name ->
            val f = File(run, name)
            if (f.exists()) {
                f.deleteRecursively()
                Log.i(TAG, "removed legacy $name from the run directory")
            }
        }
        marker.writeText("3")
    }

    /** Per-bundle fixups: permissions, and the toolchain's symlinks from its manifest. */
    private fun afterExtract(assetName: String) {
        when (assetName) {
            Constants.ASSET_QEMU_BUNDLE ->
                chmodTree(File(runDir, Constants.QEMU_DIR_REL), execAll = true)
            Constants.ASSET_SCRIPTS_BUNDLE -> {
                // fetch.sh, userland/build.sh, mkjobs.py (invoked as ./mkjobs.py), unzbm, ...
                chmodTree(File(runDir, Constants.SCRIPTS_DIR_REL), execAll = true)
                patchMkJobs()
            }
            Constants.ASSET_TOOLCHAIN_BUNDLE -> {
                chmodTree(File(runDir, Constants.TOOLCHAIN_DIR_REL), execAll = false)
                createSymlinks()
            }
        }
    }

    /**
     * Restore the permission bits the zip format does not carry.
     *
     * A ZipEntry has no mode field, so `FileOutputStream` recreates every path with 0644. Without
     * this pass `bash`, `python3.14`, `7zz` and `unzbm` all land non-executable and the very
     * first `ProcessBuilder.start()` dies with error=13 -- which looks exactly like the Android 10
     * "app home directory is not executable" policy, so both have to be handled.
     *
     * Directories get 0755 (a missing x bit on a parent also yields EACCES), files get 0755 when
     * they may be run and 0644 otherwise. [execAll] is used for the bundles that are nothing but
     * scripts and binaries (qemu, hackios); the toolchain is mixed (python's .py/.so tree), so it
     * is filtered by [needsExecBit] instead of being blanket-chmod'ed.
     */
    private fun chmodTree(root: File, execAll: Boolean) {
        if (!root.isDirectory) return
        // The prefix must be captured once, from the top-level bundle root. An earlier version
        // recursed with the child directory as the new `root`, so `f.relativeTo(root)` collapsed
        // "bin/bash" to just "bash" and needsExecBit() never saw the "bin" component -- every
        // toolchain binary silently stayed 0644 and every exec failed with EACCES.
        val base = root.absolutePath.trimEnd(File.separatorChar)
        fun visit(dir: File) {
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    visit(f)
                } else {
                    f.setReadable(true, false)
                    val rel = f.absolutePath.removePrefix(base)
                        .trimStart(File.separatorChar)
                        .replace(File.separatorChar, '/')
                    f.setExecutable(execAll || needsExecBit(rel), false)
                }
            }
        }
        visit(root)
    }

    /**
     * Self-heal the RWA_PACKED16 mkjobs.py bug at deploy time, so any build -- even from a regressed
     * asset -- runs the corrected script.
     *
     * 0.26.1 shipped a mkjobs.py whose rest branch did `for name in ('RWA_PACKED16',):`, shadowing the
     * loop variable that held the plist file name. Every on-demand job was then written to a single
     * illegal file `/redwood/LaunchDaemons/RWA_PACKED16` and launchd failed to bootstrap it
     * (error 108); the real per-daemon plists (mobileactivationd, securityd, personad …) were never
     * written, so lsd spun forever on `failed lookup: com.apple.mobileactivationd` -- the "卡在 lsd"
     * symptom. The fix is one line: write `{label}.plist` instead of the shadowed `{name}`. This is
     * idempotent: the buggy line is absent from an already-fixed file, so it is a no-op there.
     */
    private fun patchMkJobs() {
        val f = File(runDir, "${Constants.SCRIPTS_DIR_REL}/userland/mkjobs.py")
        if (!f.isFile) return
        val text = f.readText()
        val buggy = "with open(f'{dest}/{name}', 'wb') as f:"
        val fixed = "with open(f'{dest}/{label}.plist', 'wb') as f:"
        if (text.contains(buggy)) {
            f.writeText(text.replace(buggy, fixed))
            Log.i(TAG, "patchMkJobs: RWA_PACKED16 bug fixed in extracted mkjobs.py")
        }
    }

    /** True for paths that must be executable: shell scripts, and anything under a bin/ or
     * libexec/ directory (coreutils/findutils/diffutils multi-call links live there). */
    private fun needsExecBit(rel: String): Boolean {
        if (rel.endsWith(".sh")) return true
        val dir = rel.substringBeforeLast('/', "")
        return dir == "bin" || dir.endsWith("/bin") ||
            dir == "sbin" || dir.endsWith("/sbin") ||
            dir == "libexec" || dir.contains("/libexec")
    }

    /** Recreate the multi-call symlinks (cp/mv/rm/..., 7z/7za/7zr, python3) from _symlinks.txt.
     * Each line is "target<TAB>linkpath", both relative to the toolchain root. */
    private fun createSymlinks() {
        val manifest = File(runDir, "${Constants.TOOLCHAIN_DIR_REL}/_symlinks.txt")
        if (!manifest.exists()) return
        var made = 0
        manifest.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val (target, linkRel) = line.split('\t', limit = 2).takeIf { it.size == 2 }
                ?: return@forEachLine
            val link = File(runDir, "${Constants.TOOLCHAIN_DIR_REL}/$linkRel")
            link.parentFile?.mkdirs()
            if (link.exists() && !Files.isSymbolicLink(link.toPath())) link.delete()
            try {
                Files.createSymbolicLink(link.toPath(), Paths.get(target))
                made++
            } catch (t: Throwable) {
                Log.w(TAG, "symlink $linkRel -> $target failed: ${t.message}")
            }
        }
        Log.i(TAG, "toolchain symlinks created: $made")
    }

    // ---- probes -------------------------------------------------------------------------------

    private fun scriptsMarker(): String = "${Constants.SCRIPTS_DIR_REL}/userland/build.sh"

    private fun toolchainBin(): String = "${Constants.TOOLCHAIN_BIN_REL}/python3.14"

    /** First file the unzbm self-test expects to find once the probe bundle is unpacked. */
    private fun probeFile(): String = "${Constants.PROBE_DIR_REL}/version.plist:com.apple.decmpfs"

    /** Path to the working directory that holds kernel/loader/uefi/payload/ram.img/esp. */
    fun runDirectory(): File = runDir

    /**
     * 重置 UEFI NVRAM（QEMU_VARS.fd）：用 APK 里的原始副本覆盖运行目录里的那一份。
     *
     * 为什么需要它：QEMU 的 pflash vars 是可写盘，上一次误进了 EDK2 内置 Shell 后，
     * `Boot0002`（EFI Shell）会被写进 NVRAM 并排到启动顺序首位，之后每次开机都直接进
     * Shell、屏幕上什么都没有，看起来就是「黑屏卡死」。上游 run-darwin.sh 用
     * QEMU_VARS_RESET=1 重刷一份 64 MiB、全 0xFF 的 vars.fd 解决；这里等价地重拷资产。
     */
    fun resetEfiVars(): Boolean {
        val vars = File(runDir.apply { mkdirs() }, Constants.ASSET_VARS)
        val ok = copyAsset(Constants.ASSET_VARS, vars)
        if (ok) AppLog.w(TAG, "UEFI NVRAM 已重置：${vars.absolutePath}（丢弃可能导致每次进 EFI Shell 的启动项）")
        else AppLog.w(TAG, "UEFI NVRAM 重置失败：资产 ${Constants.ASSET_VARS} 缺失")
        return ok
    }

    /** The unpacked HackiOS scripts (ios165/, userland/, tools/, prebuilt/, xnu-env.sh). */
    fun scriptsDir(): File = File(runDir, Constants.SCRIPTS_DIR_REL)

    /** The unpacked Termux toolchain (bin/, lib/, usr/). */
    fun toolchainDir(): File = File(runDir, Constants.TOOLCHAIN_DIR_REL)

    private fun copyAsset(name: String, dest: File): Boolean {
        return try {
            context.assets.open(name).use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "asset missing or copy failed: $name (${t.message})")
            false
        }
    }

    private fun copyRaw(src: File, dest: File): Boolean {
        return try {
            src.inputStream().use { input -> FileOutputStream(dest).use { out -> input.copyTo(out) } }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "raw copy failed: ${src.name} -> ${dest.name} (${t.message})")
            false
        }
    }

    companion object {
        private const val TAG = "AssetDeployer"

        /** Bump when the *post-extraction* fixups change (permission bits, symlinks, layout), so
         * that installing an updated APK re-unpacks the same zip bytes once and applies them.
         * v2: chmod every bin/ entry + 0755 on directories (zip carries no mode bits).
         * v3: bundle top folders (hackios/, toolchain/), legacy-layout cleanup, targetSdk 28.
         * v4: chmodTree relative-path fix -- v2/v3 computed "bash" instead of "bin/bash" while
         *     recursing, so the whole toolchain stayed 0644 (EACCES on every exec).
         * v5: unzbm replaced by a bionic build (glibc static dies with SIGSYS inside the app's
         *     seccomp filter), plus the new probe/ directory that feeds it real LZBITMAP data.
         * v6: build.sh's cp -al replaced by hl() -- SELinux denies link(2) on app_data_file, so
         *     the extracted trees are copied instead of linked on the device.
         * v7: build.sh's `ln -f` (dyld shared caches) replaced the same way -- link(2) is denied
         *     there too, and cp -al has no -f, so the destination is cleared first.
         * v8: qemu-android-bundle.zip rebuilt with its 16 library symlinks materialised. A zip
         *     cannot carry a symlink, so Android's ZipInputStream had been writing 0-byte stubs
         *     (libz.so.1, libglib-2.0.so.0, ...), the linker refused the exec, and QEMU died
         *     before it ever opened -serial. Bumped (not just re-sized) so the old tree is
         *     re-extracted even if the size read fails and the stamp looks unchanged.
         * v9: qemu bundle now carries its datadir (share/qemu, the option ROM files). QEMU loads
         *     them by name from that directory only -- they appear in no DT_NEEDED list -- and the
         *     compiled-in default is a Termux prefix that does not exist on Android, so 11.1
         *     stopped at 'failed to find romfile "efi-virtio.rom"' with exit=1 and an empty
         *     serial.log. Bumped so the tree gains share/ without a manual clear.
         * v10: fetch.sh gained `umask 022` and build.sh a mode fixup for the cryptex trees. The
         *     cryptex has no manifest: 7z extracts it under the app's umask 077, so every cryptex
         *     file -- libobjc-trampolines.dylib first among them -- reached the image as 0600 and
         *     SpringBoard died with "open() failed with errno=13" (EACCES) on a graft that was
         *     correct. Bumped so the tree is re-extracted and the old scripts are replaced; the
         *     build itself then has to be re-run once (the system image carries the bad modes).
     * v11: hackios-scripts.zip now carries the OpenGL ES runtime for the window server --
     *     libOSMesa.8.dylib (Mesa softpipe) and libredwood-gl.dylib, plus the gl_test /
     *     u2f_test probes -- and the refreshed libredwood-sb.dylib. QemuRunner.kt adds gl=1
     *     to the boot-args so the on-device build (GL=1 PREBUILT=1) bakes them into root.apfs.
     *     Bumped so existing installs re-extract the scripts and re-run the image build.
     * v12: 0.25.0 FAST boot. build.sh gained a FAST=1 branch (16 desktop jobs instead of
     *     REST=1's ~490 on-demand plists, QUIET=1, no `cpu_test 240 &`), plus rc.jobs.fast
     *     and rc.jobs.settings. IMAGE_REV deliberately stays 3: system.apfs is unchanged, so
     *     an existing 3rd-revision image still boots -- only the 3.5 MB scripts need re-unpacking.
     *     Bumped so old installs re-extract the new build.sh.
     * v14: FAST 作业集补上 com.apple.mobileactivationd（上游 one-shot-build 的组件 2 修复）。
     *     FAST=1 时它不在 JOBS 里就永远不会注册，lsd 反复查 "No such process" 形成风暴；
     *     root.apfs 会因此变化，所以 Constants.IMAGE_REV 同步 3→5，旧镜像会被拒绝启动。
     * v15: 2026-11-05 真机日志显示 mobileactivationd 已注册但查 com.apple.securityd 时被拒，
     *     以 SIGTRAP 连崩 12 次；FAST 作业集再补 securityd 与 SystemConfiguration.configd，
     *     IMAGE_REV 5→6。
     * v16: 同一崩溃序列里 mobileactivationd 还查了 com.apple.timed.xpc；补 com.apple.timed，
     *     IMAGE_REV 6→7，一次补齐激活链依赖。
     * v17: configd 的 job 名写错：FAST 里传的是 MachService 名
     *     com.apple.SystemConfiguration.configd，而 LaunchDaemon 的 label 是
     *     com.apple.configd，plist 一直没生成；改正后 IMAGE_REV 7→8。 */
        private const val LAYOUT_VERSION = 17
    }
}
