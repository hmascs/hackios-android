package com.android.gudao.iphoneos.engine

import java.io.File

/**
 * 宿主 CPU 拓扑探测（0.29.5）。
 *
 * 0.26.0 的「宿主算力池」只会拼 `0-(N-1)` 掩码。这在两种真机上都是错的：
 *
 *  1. **big.LITTLE 反着钉**：编号最小的核通常是小核。8 核设备上选「4 核算力池」得到
 *     `0-3`，恰好把 QEMU 钉死在四个小核上，比不钉还慢；16 核设备上 `0-7` 也常整段落在小核。
 *  2. **被系统 cpuset 拒绝**：Android 的 top-app / restricted cpuset 往往只放行一部分核，
 *     `sched_setaffinity` 直接 EPERM/EINVAL，[QemuAffinity] 于是整体降级，算力池只剩 TB 缓存。
 *
 * 这里只读两个到处都有的只读文件，既不需要 root 也不需要 SELinux 放行：
 *
 *  - `/proc/self/status` 的 `Cpus_allowed_list:` —— 内核已经算好的「本进程此刻真正能用的核」。
 *    它就是 taskset 掩码的上界：在这个集合里选，`sched_setaffinity` 才可能成功。
 *  - `/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq` —— 每核最高频，用来区分大/小核。
 *    部分内核不导出（返回空），此时退化成「按编号顺序」，不报错。
 *
 * 纯 Kotlin，所有解析函数都能在 JVM 单测里直接断言。
 */
object HostCpus {

    /**
     * 大核判定：最高频的 90% 以上。75% 太松——骁龙 8 Gen2 这类 1+4+3 的 SoC 上，3.5 GHz 的
     * 中核能到 4.3 GHz prime 核的 82%，会被误判成大核；90% 只留下真正的 prime/大核簇。
     * 这个字段只用于日志与说明，选核本身靠 [rankByFreq] 的全序，不受阈值影响。
     */
    private const val BIG_RATIO = 0.90

    data class Topology(
        /** 本进程允许调度的核，升序；空表示没读到，调用方应视为「未知」而非「没有核」。 */
        val allowed: List<Int>,
        /** 按算力从高到低排的核；读不到频率时等于 [allowed]。 */
        val ranked: List<Int>,
        /** 其中判定为大核的那些，升序。 */
        val big: List<Int>,
        /** 数据来源，写日志用：`sysfs` / `proc` / `none`。 */
        val source: String,
    ) {
        val known: Boolean get() = allowed.isNotEmpty()
    }

    val UNKNOWN = Topology(emptyList(), emptyList(), emptyList(), "none")

    /**
     * 解析 `/proc/self/status` 里的 `Cpus_allowed_list: 0-6,8`。
     * 解析不出来返回空列表；调用方按 [UNKNOWN] 处理，绝不能当成「零个核」。
     */
    fun parseAllowed(status: String): List<Int> {
        val line = status.lineSequence().firstOrNull { it.startsWith("Cpus_allowed_list:") } ?: return emptyList()
        return parseCpuList(line.substringAfter(':').trim())
    }

    /** 解析 `0-6,8` 形式的核列表，升序去重。宽容处理空项与倒序区间。 */
    fun parseCpuList(spec: String): List<Int> {
        val out = sortedSetOf<Int>()
        for (part in spec.split(',')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val dash = p.indexOf('-')
            if (dash < 0) {
                p.toIntOrNull()?.let { if (it >= 0) out += it }
                continue
            }
            val lo = p.substring(0, dash).trim().toIntOrNull() ?: continue
            val hi = p.substring(dash + 1).trim().toIntOrNull() ?: continue
            if (lo < 0 || hi < 0) continue
            for (i in minOf(lo, hi)..maxOf(lo, hi)) out += i
        }
        return out.toList()
    }

    /** 把核列表格式化成 `0-3,6,8`。taskset / QEMU 的掩码都用这个形式。 */
    fun formatCpuList(ids: Collection<Int>): String {
        val sorted = ids.filter { it >= 0 }.toSortedSet().toList()
        if (sorted.isEmpty()) return ""
        val sb = StringBuilder()
        var start = sorted[0]
        var prev = sorted[0]
        for (i in 1..sorted.size) {
            val cur = if (i < sorted.size) sorted[i] else Int.MIN_VALUE
            if (cur == prev + 1) { prev = cur; continue }
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(if (start == prev) "$start" else "$start-$prev")
            if (i < sorted.size) { start = cur; prev = cur }
        }
        return sb.toString()
    }

    /** 从 频率表 推出按算力降序的核列表；同频按编号升序，保证结果稳定可测。 */
    fun rankByFreq(freqs: Map<Int, Long>): List<Int> =
        freqs.entries.sortedWith(compareByDescending<Map.Entry<Int, Long>> { it.value }.thenBy { it.key })
            .map { it.key }

    /**
     * 选出算力池真正使用的核：优先 [ranked] 的顺序（大核在前），但只能在 [allowed] 里挑。
     * [pool] 为 0 或负数表示「不限制」，直接返回 [allowed]。
     *
     * 结果升序，便于和系统 cpuset 对齐；空 [allowed] 返回空（表示未知，调用方不要拼掩码）。
     */
    fun chooseCores(allowed: List<Int>, ranked: List<Int>, pool: Int): List<Int> {
        if (allowed.isEmpty()) return emptyList()
        if (pool <= 0 || pool >= allowed.size) return allowed.sorted()
        val allowedSet = allowed.toHashSet()
        val picked = LinkedHashSet<Int>()
        for (c in ranked) if (c in allowedSet) { picked += c; if (picked.size >= pool) break }
        if (picked.size < pool) for (c in allowed.sorted()) { picked += c; if (picked.size >= pool) break }
        return picked.sorted()
    }

    /** 真机读取：只读 /proc/self/status 与 sysfs，任何异常都退化成 [UNKNOWN]。 */
    fun read(): Topology = runCatching {
        val status = File("/proc/self/status").takeIf { it.canRead() }?.readText(Charsets.UTF_8) ?: ""
        val allowed = parseAllowed(status)
        if (allowed.isEmpty()) return@runCatching UNKNOWN
        val freqs = LinkedHashMap<Int, Long>()
        for (cpu in allowed) {
            val f = File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
            val khz = f.takeIf { it.canRead() }?.readText(Charsets.UTF_8)?.trim()?.toLongOrNull()
            if (khz != null && khz > 0) freqs[cpu] = khz
        }
        if (freqs.isEmpty()) {
            return@runCatching Topology(allowed, allowed, emptyList(), "proc")
        }
        val maxF = freqs.values.max()
        val big = freqs.filterValues { it.toDouble() >= maxF * BIG_RATIO }.keys.sorted()
        Topology(allowed, rankByFreq(freqs), big, "sysfs")
    }.getOrDefault(UNKNOWN)
}
