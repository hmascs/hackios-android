package com.android.gudao.iphoneos

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.android.gudao.iphoneos.engine.AppLog
import com.android.gudao.iphoneos.engine.FrameRing
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Trusts only the experimental APK's own assets, never imported driver packages. */
internal object GpuRuntime {
    private const val TAG = "GpuRuntime"
    private const val ASSET = "gpu-android-arm64.zip"
    /**
     * 解包目标目录是 `gpu-runtime-$REV`：bundle 内容一变（换 worker 二进制）就必须 +1，
     * 否则旧目录会被当成有效产物继续用。
     * 1: 0.31.3 的 worker。2: 0.31.4 的 worker（`--frame-ring`）。
     * 3: 0.31.6 的 worker（差分回写 `HIOS_EAGL_READ_DELTA`，必须与同版 dylib 配对，否则首次
     *    读回会被 EA_BAD_COMMAND 拒绝而 fail-closed）。
     * 4: HFR2/v2 写入前失效保护；必须重新解出匹配的新 worker，不能复用旧 HFRM 目录。
     * 5: 纹理通道映射修复与命令批处理，配合本版来宾库。
     */
    private const val REV = 5
    const val PORT = 19484
    private const val MAX_RUNTIME_BYTES = 300L * 1024 * 1024
    private val safeName = Regex("[A-Za-z0-9_.+/-]+")

    fun available(context: Context): Boolean = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a") &&
        try { context.assets.open(ASSET).close(); true } catch (_: IOException) { false }

    @Synchronized
    fun prepare(context: Context, run: File): Prepared {
        check(available(context)) { "本安装包或宿主不支持 Adreno 实验后端（需要 ARM64 GPU 测试包）" }
        val destination = File(context.filesDir, "gpu-runtime-$REV")
        val bundle = File(destination, "gpu-android-arm64")
        val started = File(bundle, "start-worker.sh")
        if (!started.isFile) {
            val staging = Files.createTempDirectory(context.filesDir.toPath(), ".gpu-runtime-").toFile()
            try {
                var expanded = 0L
                var count = 0
                val names = HashSet<String>()
                ZipInputStream(context.assets.open(ASSET).buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.trimEnd('/')
                        require(++count <= 1024 && names.add(name) && safeName.matches(name) &&
                            (name == "gpu-android-arm64" || name.startsWith("gpu-android-arm64/")) &&
                            name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "非法 GPU 运行时条目" }
                        val file = File(staging, name)
                        if (entry.isDirectory) file.mkdirs() else {
                            file.parentFile!!.mkdirs()
                            file.outputStream().buffered().use { out ->
                                val block = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zip.read(block)
                                    if (n < 0) break
                                    expanded += n
                                    require(expanded <= MAX_RUNTIME_BYTES) { "GPU 运行时解包超出上限" }
                                    out.write(block, 0, n)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
                val prepared = File(staging, "gpu-android-arm64")
                check(File(prepared, "start-worker.sh").isFile &&
                    File(prepared, "bin/hackios-zink-session").setExecutable(true, true)) { "GPU 运行时资产不完整" }
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }
        if (!started.isFile) throw FileNotFoundException("GPU 启动脚本缺失")
        val prepared = Prepared(started, run)
        prepared.selfTest()
        return prepared
    }

    internal class Prepared(private val script: File, private val run: File) {
        private fun launch(log: File, vararg arguments: String): Process =
            ProcessBuilder(listOf("/system/bin/sh", script.absolutePath, "Adreno") + arguments)
                .directory(run).redirectErrorStream(true).redirectOutput(log).start()

        fun selfTest() {
            val log = File(run, "gpu-self-test.log")
            val process = launch(log, "--self-test")
            try {
                if (!process.waitFor(25, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    throw IOException("Adreno 自检失败：${readTail(log)}")
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }

        fun start(): Session {
            val directory = Files.createTempDirectory(run.toPath(), "g-").toFile()
            val socket = File(directory, "gpu.sock")
            val log = File(run, "gpu-worker.log")
            var process: Process? = null
            var ringFile: File? = null
            try {
                require(socket.absolutePath.toByteArray(Charsets.UTF_8).size < 108) { "GPU socket 路径过长" }
                // A6 帧环：只有开关打开时才建文件、传 `--frame-ring`。worker 对未知参数可能
                // 直接启动失败，所以关闭时命令行必须与以前逐字一致（只有 --listen-unix）。
                val ring = if (Constants.FRAME_RING_ENABLED) {
                    val file = File(run, "gpu-frames.ring")
                    if (FrameRing.create(file, Constants.SCREEN_W, Constants.SCREEN_H, Constants.SCREEN_W * 4)) {
                        ringFile = file
                        file
                    } else {
                        // 建环失败不该拖垮启动：worker 侧协议也允许静默降级，本次继续用 screendump。
                        AppLog.w(TAG, "帧环创建失败，本次仍按 screendump 取帧")
                        null
                    }
                } else null
                val args = buildList {
                    add("--listen-unix")
                    add(socket.absolutePath)
                    if (ring != null) {
                        add("--frame-ring")
                        add(ring.absolutePath)
                    }
                }
                process = launch(log, *args.toTypedArray())
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (process.isAlive && SystemClock.elapsedRealtime() < deadline) {
                    if (socket.exists() && readTail(log).contains("EAGL worker: listening on ${socket.absolutePath}")) {
                        return Session(process, socket, directory, log, ring)
                    }
                    Thread.sleep(35)
                }
                throw IOException("GPU worker 未就绪：${readTail(log)}")
            } catch (e: Exception) {
                process?.destroyForcibly()
                runCatching { ringFile?.delete() }
                directory.deleteRecursively()
                throw e
            }
        }
    }

    internal class Session(
        private val process: Process,
        val socket: File,
        private val directory: File,
        private val log: File,
        /** A6 帧环文件；未启用或创建失败时为 null。上层据此决定是否接管取帧。 */
        val frameRing: File? = null,
    ) {
        fun failure(): String? = if (process.isAlive) null else "Adreno worker 已退出 (${process.exitValue()})：${readTail(log)}"
        fun close() {
            if (process.isAlive) process.destroyForcibly()
            directory.deleteRecursively()
            runCatching { frameRing?.delete() }
        }
    }

    private fun readTail(file: File): String = runCatching {
        java.io.RandomAccessFile(file, "r").use { input ->
            val count = input.length().coerceAtMost(4096).toInt()
            input.seek(input.length() - count)
            ByteArray(count).also { input.readFully(it) }.toString(Charsets.UTF_8).trim()
        }
    }.getOrDefault("无日志")
}
