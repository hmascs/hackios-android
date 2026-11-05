package com.android.gudao.iphoneos.mcp

/**
 * MCP 服务的持久化配置。
 *
 * 这个 App 不再内置对话式 AI：它对外开一个标准 [Model Context Protocol](https://modelcontextprotocol.io)
 * 端口，任何支持 MCP 的客户端（Claude Desktop、Cline、Cursor、MCP Inspector……）都可以连上来，
 * 用 `tools/list` 看到整条链路的工具，用 `tools/call` 驱动这台手机上的 QEMU。
 *
 * 默认只监听 127.0.0.1，靠 `adb forward tcp:8765 tcp:8765` 把端口映射到电脑，因此不需要
 * 任何网络权限之外的东西，也不会把控制口暴露到局域网。打开「局域网」后绑定 0.0.0.0，
 * 此时强制要求 Bearer token（[token]），否则同网段任何人都能启停 QEMU、删除镜像。
 */
data class McpConfig(
    /** 服务总开关。关掉后不监听任何端口。 */
    val enabled: Boolean = true,
    val port: Int = DEFAULT_PORT,
    /** true = 绑定 0.0.0.0（局域网可达，强制 token）；false = 仅 127.0.0.1（adb forward）。 */
    val bindLan: Boolean = false,
    /** 鉴权令牌。loopback 连接可省略，非 loopback 必须带 `Authorization: Bearer <token>`。 */
    val token: String = "",
    /** 工具箱权限：false 时只暴露只读工具。 */
    val allowWrite: Boolean = false,
    /** 完全访问：写工具之外再开放路径/命令边界与 delete_chain。以 [allowWrite] 为前提。 */
    val fullAccess: Boolean = false,
) {
    val effectiveFull: Boolean get() = allowWrite && fullAccess
    val bindHost: String get() = if (bindLan) "0.0.0.0" else "127.0.0.1"

    /** 给本机 adb forward 之后用的地址。 */
    val url: String get() = "http://127.0.0.1:$port/mcp"
    val adbCommand: String get() = "adb forward tcp:$port tcp:$port"

    /** 局域网模式下给客户端看的完整地址（token 通过 header 传，不写进 URL）。 */
    fun lanUrl(hostIp: String): String = "http://$hostIp:$port/mcp"

    companion object {
        const val DEFAULT_PORT = 8765
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        /** 我们实现的主协议版本；客户端请求旧版本时按它自己的版本回应。 */
        const val PROTOCOL_VERSION = "2025-03-26"
        val SUPPORTED_VERSIONS = setOf("2025-03-26", "2025-06-18", "2024-11-05")

        const val SERVER_NAME = "hackios-mcp"
        const val SERVER_VERSION = "0.26.1"

        /** 删除整条链路必须一字不差地传这个确认串，避免模型一次误调用把 6 GB 固件删掉。 */
        const val CONFIRM_DELETE = "DELETE-CHAIN"

        fun randomToken(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
