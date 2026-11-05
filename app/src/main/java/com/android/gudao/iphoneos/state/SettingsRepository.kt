package com.android.gudao.iphoneos.state

import android.content.Context
import com.android.gudao.iphoneos.Constants
import com.android.gudao.iphoneos.mcp.McpConfig

/**
 * Persisted preferences. Deliberately tiny: everything here survives a restart, everything else is
 * derived from what is on disk.
 *
 * MCP 部分保存端口、绑定方式与鉴权令牌。令牌存在应用私有的 SharedPreferences 里（只有本 App
 * 能读），不会写进日志、导出文件或 `get_config` 的返回值之外的地方——它不是什么高价值密钥，
 * 只是防止同一局域网里其他人碰这台手机的 QEMU。
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("hackios", Context.MODE_PRIVATE)

    fun load(): SettingsUi = SettingsUi(
        smp = prefs.getInt(KEY_SMP, Constants.SMP_DEFAULT).coerceIn(Constants.SMP_MIN, Constants.SMP_MAX),
        hostCores = prefs.getInt(KEY_HOST_CORES, Constants.HOST_CORES_DEFAULT)
            .coerceIn(Constants.HOST_CORES_MIN, Constants.HOST_CORES_MAX),
        netEnabled = prefs.getBoolean(KEY_NET, Constants.NET_DEFAULT),
        themeMode = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.System.name)!!)
        }.getOrDefault(ThemeMode.System),
        followLog = prefs.getBoolean(KEY_FOLLOW, true),
        glassStrength = runCatching {
            GlassStrength.valueOf(prefs.getString(KEY_GLASS, GlassStrength.Standard.name)!!)
        }.getOrDefault(GlassStrength.Standard),
        mcp = McpConfig(
            enabled = prefs.getBoolean(KEY_MCP_ENABLED, true),
            port = prefs.getInt(KEY_MCP_PORT, McpConfig.DEFAULT_PORT)
                .coerceIn(McpConfig.MIN_PORT, McpConfig.MAX_PORT),
            bindLan = prefs.getBoolean(KEY_MCP_LAN, false),
            token = ensureToken(),
            allowWrite = prefs.getBoolean(KEY_MCP_WRITE, false),
            fullAccess = prefs.getBoolean(KEY_MCP_FULL, false),
        ),
        qemuFlags = prefs.getString(KEY_QEMU_FLAGS, "").orEmpty(),
        bootArgs = prefs.getString(KEY_BOOT_ARGS, "").orEmpty(),
        mttcg = prefs.getBoolean(KEY_MTTCG, Constants.MTTCG_DEFAULT),
        autoFallback = prefs.getBoolean(KEY_AUTO_FALLBACK, Constants.AUTO_FALLBACK_DEFAULT),
        perfGateDismissed = prefs.getBoolean(KEY_PERF_GATE, false),
    )

    /** 第一次启动就生成一个令牌；之后沿用，避免每次重启端口都要重配客户端。 */
    private fun ensureToken(): String {
        val saved = prefs.getString(KEY_MCP_TOKEN, "").orEmpty()
        if (saved.isNotBlank()) return saved
        val fresh = McpConfig.randomToken()
        prefs.edit().putString(KEY_MCP_TOKEN, fresh).apply()
        return fresh
    }

    fun setSmp(value: Int) {
        prefs.edit().putInt(KEY_SMP, value.coerceIn(Constants.SMP_MIN, Constants.SMP_MAX)).apply()
    }

    /**
     * 宿主算力池。只影响下次 QEMU 命令行的 `taskset -c` 掩码与 `tb-size`，**不进 payload、
     * 不需要重新构建镜像**，也不影响 Guest vCPU 数。
     */
    fun setHostCores(value: Int) {
        prefs.edit()
            .putInt(KEY_HOST_CORES, value.coerceIn(Constants.HOST_CORES_MIN, Constants.HOST_CORES_MAX))
            .apply()
    }

    /** 多线程 TCG 开关。关闭后回到 0.23.0 的单线程基线，供排障对比。 */
    fun setMttcg(value: Boolean) {
        prefs.edit().putBoolean(KEY_MTTCG, value).apply()
    }

    /** 卡住时自动降核重启一次的保护开关。 */
    fun setAutoFallback(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_FALLBACK, value).apply()
    }

    /** 0.29.6：性能闸门「不再弹出」。 */
    fun setPerfGateDismissed(value: Boolean) {
        prefs.edit().putBoolean(KEY_PERF_GATE, value).apply()
    }

    fun setNet(value: Boolean) {
        prefs.edit().putBoolean(KEY_NET, value).apply()
    }

    fun setTheme(value: ThemeMode) {
        prefs.edit().putString(KEY_THEME, value.name).apply()
    }

    fun setFollow(value: Boolean) {
        prefs.edit().putBoolean(KEY_FOLLOW, value).apply()
    }

    fun setGlassStrength(value: GlassStrength) {
        prefs.edit().putString(KEY_GLASS, value.name).apply()
    }

    // ---- MCP ----------------------------------------------------------------------------------

    fun setMcpEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_MCP_ENABLED, value).apply()
    }

    fun setMcpPort(value: Int) {
        prefs.edit().putInt(KEY_MCP_PORT, value.coerceIn(McpConfig.MIN_PORT, McpConfig.MAX_PORT)).apply()
    }

    fun setMcpBindLan(value: Boolean) {
        prefs.edit().putBoolean(KEY_MCP_LAN, value).apply()
    }

    fun regenerateToken(): String {
        val fresh = McpConfig.randomToken()
        prefs.edit().putString(KEY_MCP_TOKEN, fresh).apply()
        return fresh
    }

    /** MCP 的 set_config(mcp_token) 可以直接设一个新值，或传 regenerate 走 [regenerateToken]。 */
    fun setMcpToken(value: String) {
        prefs.edit().putString(KEY_MCP_TOKEN, value.trim()).apply()
    }

    fun setMcpAllowWrite(value: Boolean) {
        prefs.edit().putBoolean(KEY_MCP_WRITE, value).apply()
        // 完全访问以写权限为前提：关掉写权限时它必须一起失效。
        if (!value) prefs.edit().putBoolean(KEY_MCP_FULL, false).apply()
    }

    fun setMcpFullAccess(value: Boolean) {
        prefs.edit().putBoolean(KEY_MCP_FULL, value).apply()
    }

    fun setQemuFlags(value: String) {
        prefs.edit().putString(KEY_QEMU_FLAGS, value.trim()).apply()
    }

    fun setBootArgs(value: String) {
        prefs.edit().putString(KEY_BOOT_ARGS, value.trim()).apply()
    }

    /**
     * `reset_config` / delete_chain(scope=config)：把 QEMU 与外观设置恢复默认，
     * 但**保留 MCP 端口配置**，否则调用方会在重置的瞬间把自己关掉。
     */
    fun resetQemuSettings() {
        prefs.edit()
            .putInt(KEY_SMP, Constants.SMP_DEFAULT)
            .putInt(KEY_HOST_CORES, Constants.HOST_CORES_DEFAULT)
            .putBoolean(KEY_NET, Constants.NET_DEFAULT)
            .putString(KEY_THEME, ThemeMode.System.name)
            .putBoolean(KEY_FOLLOW, true)
            .putString(KEY_GLASS, GlassStrength.Standard.name)
            .putString(KEY_QEMU_FLAGS, "")
            .putString(KEY_BOOT_ARGS, "")
            .putBoolean(KEY_MTTCG, Constants.MTTCG_DEFAULT)
            .putBoolean(KEY_AUTO_FALLBACK, Constants.AUTO_FALLBACK_DEFAULT)
            .apply()
    }

    private companion object {
        const val KEY_SMP = "smp"
        const val KEY_HOST_CORES = "host_cores"
        const val KEY_NET = "net"
        const val KEY_MTTCG = "mttcg"
        const val KEY_AUTO_FALLBACK = "auto_fallback"
        const val KEY_THEME = "theme"
        const val KEY_FOLLOW = "follow"
        const val KEY_GLASS = "glass"
        const val KEY_MCP_ENABLED = "mcp_enabled"
        const val KEY_MCP_PORT = "mcp_port"
        const val KEY_MCP_LAN = "mcp_lan"
        const val KEY_MCP_TOKEN = "mcp_token"
        const val KEY_MCP_WRITE = "mcp_write"
        const val KEY_MCP_FULL = "mcp_full"
        const val KEY_QEMU_FLAGS = "qemu_flags"
        const val KEY_BOOT_ARGS = "boot_args"
        const val KEY_PERF_GATE = "perf_gate_dismissed"
    }
}
