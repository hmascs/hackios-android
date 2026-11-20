package com.android.gudao.iphoneos

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/** Public files are backups only. Both the base and backup are snapshotted before packing. */
internal object GpuImageStore {
    const val MAX_ROOT = 48L shl 20
    const val MAX_STAGE = 48L shl 20
    private val sha = Regex("[0-9a-f]{64}")

    fun directory(publicRoot: File, key: String): File {
        require(sha.matches(key))
        var dir = publicRoot
        for (name in listOf("gpu", "adreno-v2", key)) {
            dir = File(dir, name)
            if (!dir.mkdir() && !Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS)) {
                throw IOException("Adreno 共享缓存目录无法使用：${dir.name}")
            }
        }
        return dir
    }

    fun copyBounded(source: File, destination: File, limit: Long) {
        if (!Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS) || source.length() !in 1..limit) {
            throw IOException("不是有效的 Adreno 镜像/快照：${source.name}")
        }
        Files.newInputStream(source.toPath(), NOFOLLOW_LINKS).use { input ->
            Files.newOutputStream(destination.toPath(), CREATE_NEW, WRITE).use { output ->
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    if (size > limit) throw IOException("Adreno 快照超限：${source.name}")
                    output.write(buffer, 0, n)
                }
                if (size == 0L) throw IOException("Adreno 快照为空：${source.name}")
            }
        }
    }

    /** 公共目录里的一份材料清单：不含时间戳，便于比对与复现。 */
    fun materialsManifest(key: String, stageSha: String, gpuSha: String, dylibSha: String): String {
        require(sha.matches(key))
        for (value in listOf(stageSha, gpuSha, dylibSha)) require(sha.matches(value))
        return buildString {
            appendLine("hackios gpu materials v1")
            appendLine("key=$key")
            appendLine("stage.tar sha256=$stageSha")
            appendLine("root-gpu.apfs sha256=$gpuSha")
            appendLine("gpu-libredwood-gl.dylib sha256=$dylibSha")
            appendLine("note=stage.tar 用 APK 自带 mkapfs.py 打包成名为 root.apfs 的文件，必须与 OSMesa 根镜像逐字节一致")
        }
    }

    /**
     * 写清单：先写临时文件再原子改名，已有清单不覆盖（公共目录里的文件既是备份也是证据）。
     * 失败只影响清单，不影响镜像与备份本身。
     */
    fun publishManifest(target: File, text: String) {
        if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) return
        val scratch = Files.createTempDirectory(target.parentFile!!.toPath(), ".manifest-")
        val tmp = scratch.resolve("materials.txt")
        try {
            Files.newOutputStream(tmp, CREATE_NEW, WRITE).use { it.write(text.toByteArray()) }
            try {
                Files.move(tmp, target.toPath())
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Another publisher won; keep the file that is there.
            }
        } finally {
            Files.deleteIfExists(tmp)
            Files.deleteIfExists(scratch)
        }
    }

    /** Never replace an existing user-visible file. The caller always verifies it before reuse. */
    fun publish(source: File, target: File, limit: Long) {
        if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) return
        val scratch = Files.createTempDirectory(target.parentFile!!.toPath(), ".publish-")
        val tmp = scratch.resolve("copy").toFile()
        try {
            copyBounded(source, tmp, limit)
            try {
                Files.move(tmp.toPath(), target.toPath())
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                // Another publisher won. Readers never trust the public bytes as-is.
            }
        } finally {
            Files.deleteIfExists(tmp.toPath())
            Files.deleteIfExists(scratch)
        }
    }
}
