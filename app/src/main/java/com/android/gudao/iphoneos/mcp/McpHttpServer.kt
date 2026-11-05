package com.android.gudao.iphoneos.mcp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 一个只依赖 JDK socket 的 MCP 服务端。
 *
 * Android 运行时没有 `com.sun.net.httpserver.HttpServer`，也不该为了一个调试端口再塞一个
 * Netty/Ktor 进来（APK 已经 130 MB）。所以这里手写最小 HTTP/1.1：读请求行 + 头 + Content-Length，
 * 然后把 JSON-RPC 交给 [McpProtocol]。整段代码只用 java.net / org.json，可以在 JVM 单测里
 * 起真实端口、用 HttpURLConnection 打完整的 initialize → tools/list → tools/call。
 *
 * 同时支持两代 MCP 传输：
 *  - 2025-03-26 Streamable HTTP：`POST /mcp` 直接回 application/json（或按 Accept 回单条 SSE），
 *    `GET /mcp` 是可保持的 SSE 通知流，`DELETE /mcp` 结束会话，握手回 `Mcp-Session-Id`。
 *  - 2024-11-05 HTTP+SSE：`GET /sse` 先推一条 `event: endpoint`，客户端再把消息 POST 到
 *    `/message?sessionId=...`，响应从那条 SSE 连接推回去。
 *
 * 生命周期由 AppViewModel 持有：进程活着端口就在。App 退到后台被系统回收时端口随之关闭，
 * 所以排障时保持 App 在前台（QEMU 运行时本来就会 keepScreenOn）。
 */
class McpHttpServer(
    private val protocol: McpProtocol,
    private val config: () -> McpConfig,
    /** 一行文本事件，供设置页显示「最近一次调用 / 错误」。 */
    private val onEvent: (String) -> Unit = {},
) {

    data class StartState(val ok: Boolean, val host: String, val port: Int, val message: String)

    @Volatile private var server: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    private var executor = Executors.newCachedThreadPool { r -> Thread(r, "mcp-http").apply { isDaemon = true } }

    /** sessionId → 一条打开的 SSE 连接。旧版 /message 要靠它把响应推回去。 */
    private val sessions = ConcurrentHashMap<String, SseSession>()

    @Volatile var running = false
        private set
    @Volatile var boundHost: String = ""
        private set
    @Volatile var boundPort: Int = 0
        private set

    /** 累计处理的 HTTP 请求数（含健康检查）。 */
    val requests = AtomicLong(0)
    @Volatile var lastCall: String? = null
        private set
    @Volatile var lastError: String? = null
        private set

    /** 按当前配置（重新）启动监听。端口占用会明确报错，不会静默失败。 */
    @Synchronized
    fun start(): StartState {
        stopInternal()
        val cfg = config()
        if (!cfg.enabled) {
            onEvent("MCP 服务已关闭（设置 → MCP 服务）")
            return StartState(false, "", 0, "MCP 服务已关闭")
        }
        val port = cfg.port.coerceIn(McpConfig.MIN_PORT, McpConfig.MAX_PORT)
        return try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(cfg.bindHost, port))
            }
            server = socket
            running = true
            boundHost = cfg.bindHost
            boundPort = socket.localPort
            lastError = null
            executor = Executors.newCachedThreadPool { r -> Thread(r, "mcp-http").apply { isDaemon = true } }
            acceptThread = Thread({ acceptLoop(socket) }, "mcp-accept").apply {
                isDaemon = true
                start()
            }
            val msg = "MCP 已监听 $boundHost:$boundPort · ${protocol.toolsList().length()} 个工具"
            onEvent(msg)
            StartState(true, boundHost, boundPort, msg)
        } catch (t: Throwable) {
            running = false
            lastError = "端口 $port 启动失败：${t.javaClass.simpleName} ${t.message.orEmpty()}"
            onEvent(lastError!!)
            StartState(false, cfg.bindHost, port, lastError!!)
        }
    }

    @Synchronized
    fun stop() {
        stopInternal()
        onEvent("MCP 监听已停止")
    }

    private fun stopInternal() {
        running = false
        runCatching { server?.close() }
        server = null
        sessions.values.forEach { runCatching { it.socket.close() } }
        sessions.clear()
        runCatching { executor.shutdownNow() }
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                if (running) lastError = "accept 失败：${t.message.orEmpty()}"
                break
            }
            executor.execute {
                try {
                    client.soTimeout = 60_000
                    serve(client)
                } catch (t: Throwable) {
                    // 单个连接出错不能拖垮整个监听；记录一行即可。
                    lastError = "连接处理异常：${t.javaClass.simpleName} ${t.message.orEmpty()}"
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }

    // ---- HTTP ----------------------------------------------------------------------------------

    private fun serve(socket: Socket) {
        requests.incrementAndGet()
        val inp = socket.getInputStream()
        val out = socket.getOutputStream()
        val requestLine = readHeaderLine(inp) ?: return
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val h = readHeaderLine(inp) ?: break
            if (h.isEmpty()) break
            val idx = h.indexOf(':')
            if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
        }
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = if (q >= 0) target.substring(q + 1) else ""

        if (method == "OPTIONS") {
            writeCors(out)
            return
        }
        if (!authorized(headers, query, socket)) {
            writeResponse(
                out, 401, "application/json; charset=utf-8",
                rpcError(-32001, "Unauthorized", "缺少或错误的 Bearer token；到 设置 → MCP 服务 查看令牌。"),
            )
            return
        }

        when {
            method == "GET" && (path == "/" || path == "/health") -> {
                writeResponse(out, 200, "application/json; charset=utf-8", healthJson())
            }

            method == "POST" && path == "/mcp" -> postStreamable(out, inp, headers)
            method == "GET" && path == "/mcp" -> openSse(out, socket, endpoint = null, query = query)
            method == "DELETE" && path == "/mcp" -> {
                sessions.values.forEach { runCatching { it.socket.close() } }
                sessions.clear()
                writeResponse(out, 200, "application/json; charset=utf-8", "{\"jsonrpc\":\"2.0\",\"result\":{}}")
            }

            method == "GET" && path == "/sse" -> {
                val sid = "sse-" + protocol.sessionId.take(12)
                openSse(out, socket, endpoint = "/message?sessionId=$sid", query = query, forceSid = sid)
            }

            method == "POST" && path == "/message" -> postLegacy(out, inp, headers, query)

            else -> {
                writeResponse(out, 404, "application/json; charset=utf-8", rpcError(-32601, "Not Found", "没有 $method $path"))
            }
        }
    }

    /** Streamable HTTP 的主入口。 */
    private fun postStreamable(out: OutputStream, inp: java.io.InputStream, headers: Map<String, String>) {
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > MAX_BODY) {
            writeResponse(out, 413, "application/json; charset=utf-8", rpcError(-32602, "Too Large", "请求体超过 $MAX_BODY 字节"))
            return
        }
        val body = readBody(inp, len)
        record(body)
        val response = try {
            runBlocking { protocol.handle(body) }
        } catch (t: Throwable) {
            rpcError(-32603, "Internal error", t.message.orEmpty())
        }
        val extras = listOf(
            "Mcp-Session-Id" to protocol.sessionId,
            "MCP-Protocol-Version" to McpConfig.PROTOCOL_VERSION,
        )
        if (response == null) {
            // notification / response：规范规定无响应体。
            writeResponse(out, 202, null, "", extras)
        } else if (headers["accept"].orEmpty().contains("text/event-stream") &&
            !headers["accept"].orEmpty().contains("application/json")
        ) {
            writeSseOnce(out, response, extras)
        } else {
            writeResponse(out, 200, "application/json; charset=utf-8", response, extras)
        }
    }

    /** 2024-11-05 的 /message：回 202，结果从之前那条 SSE 连接推送。 */
    private fun postLegacy(out: OutputStream, inp: java.io.InputStream, headers: Map<String, String>, query: String) {
        val sid = queryParam(query, "sessionId").orEmpty()
        val session = sessions[sid]
        if (session == null) {
            writeResponse(out, 404, "application/json; charset=utf-8", rpcError(-32000, "No session", "SSE 会话 $sid 不存在，请重新连接 /sse"))
            return
        }
        val len = (headers["content-length"]?.toIntOrNull() ?: 0).coerceAtMost(MAX_BODY)
        val body = readBody(inp, len)
        record(body)
        val response = runCatching { runBlocking { protocol.handle(body) } }
            .getOrElse { rpcError(-32603, "Internal error", it.message.orEmpty()) }
        writeResponse(out, 202, null, "")
        if (response != null) session.send("event: message\ndata: $response\n\n")
    }

    /**
     * 开一条 SSE 长连接。旧版会先推 `event: endpoint` 告诉客户端往哪 POST；
     * Streamable HTTP 的 GET /mcp 只是可选的通知流，不发 endpoint。
     */
    private fun openSse(out: OutputStream, socket: Socket, endpoint: String?, query: String, forceSid: String? = null) {
        writeSseHeaders(out, listOf("Mcp-Session-Id" to protocol.sessionId))
        if (endpoint != null) {
            out.write(("event: endpoint\ndata: $endpoint\n\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
        val sid = forceSid ?: queryParam(query, "sessionId") ?: protocol.sessionId
        val session = SseSession(out, socket)
        sessions[sid] = session
        onEvent("SSE 会话建立：$sid")
        try {
            while (running && !socket.isClosed) {
                Thread.sleep(SSE_PING_MS)
                session.send(": ping\n\n")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Throwable) {
            // 客户端断开：正常退出。
        } finally {
            sessions.remove(sid)
            onEvent("SSE 会话结束：$sid")
        }
    }

    // ---- 鉴权与工具 ----------------------------------------------------------------------------

    /**
     * 仅 loopback 时免 token；一旦绑定 0.0.0.0（或请求来自非本机）就强制校验。
     * token 可放在 `Authorization: Bearer`、`X-MCP-Token` 或 URL 的 `?token=` 上。
     */
    private fun authorized(headers: Map<String, String>, query: String, socket: Socket): Boolean {
        val address = socket.inetAddress
        val loopback = address?.isLoopbackAddress == true ||
            address?.hostAddress == "127.0.0.1" || address?.hostAddress == "::1"
        val cfg = config()
        if (!cfg.bindLan && loopback) return true
        val expected = cfg.token
        if (expected.isBlank()) return true
        val got = headers["authorization"]?.removePrefix("Bearer ")?.trim()
            ?: headers["x-mcp-token"]?.trim()
            ?: queryParam(query, "token")
        return got == expected
    }

    private fun record(body: String) {
        val msg = runCatching { JSONObject(body) }.getOrNull() ?: return
        val method = msg.optString("method").ifEmpty { "batch/response" }
        val name = if (method == "tools/call") {
            msg.optJSONObject("params")?.optString("name").orEmpty()
        } else ""
        lastCall = if (name.isNotEmpty()) "$method · $name" else method
    }

    private fun healthJson(): String = JSONObject().apply {
        put("server", McpConfig.SERVER_NAME)
        put("version", McpConfig.SERVER_VERSION)
        put("protocol", McpConfig.PROTOCOL_VERSION)
        put("session", protocol.sessionId)
        put("host", boundHost)
        put("port", boundPort)
        put("tools", protocol.toolsList().length())
        put("requests", requests.get())
        put("endpoints", org.json.JSONArray(listOf("POST /mcp", "GET /mcp", "GET /sse", "POST /message", "GET /health")))
    }.toString()

    private fun rpcError(code: Int, type: String, message: String): String = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", JSONObject.NULL)
        put(
            "error", JSONObject().apply {
                put("code", code)
                put("message", "$type: $message")
            }
        )
    }.toString()

    private fun queryParam(query: String, key: String): String? = query
        .split('&')
        .firstOrNull { it.substringBefore('=') == key }
        ?.substringAfter('=', "")
        ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }

    private fun readHeaderLine(inp: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
            if (sb.length > 16_384) return sb.toString()
        }
    }

    private fun readBody(inp: java.io.InputStream, len: Int): String {
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    private fun writeResponse(
        out: OutputStream,
        code: Int,
        contentType: String?,
        body: String,
        extras: List<Pair<String, String>> = emptyList(),
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
            .appendCors()
        if (contentType != null) head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Content-Length: ").append(bytes.size).append("\r\n")
        head.append("Connection: close\r\n")
        extras.forEach { head.append(it.first).append(": ").append(it.second).append("\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }

    private fun writeSseHeaders(out: OutputStream, extras: List<Pair<String, String>> = emptyList()) {
        val head = StringBuilder()
            .append("HTTP/1.1 200 OK\r\n")
            .appendCors()
            .append("Content-Type: text/event-stream; charset=utf-8\r\n")
            .append("Cache-Control: no-cache, no-transform\r\n")
            .append("Connection: keep-alive\r\n")
        extras.forEach { head.append(it.first).append(": ").append(it.second).append("\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    /** 单条 SSE 响应（客户端 Accept 只接受 text/event-stream 时）。 */
    private fun writeSseOnce(out: OutputStream, response: String, extras: List<Pair<String, String>>) {
        writeSseHeaders(out, extras)
        out.write(("event: message\ndata: $response\n\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun writeCors(out: OutputStream) {
        val head = StringBuilder("HTTP/1.1 204 No Content\r\n").appendCors()
            .append("Content-Length: 0\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun StringBuilder.appendCors(): StringBuilder = apply {
        append("Access-Control-Allow-Origin: *\r\n")
        append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n")
        append("Access-Control-Allow-Headers: Content-Type, Authorization, X-MCP-Token, Mcp-Session-Id, MCP-Protocol-Version, Accept\r\n")
        append("Access-Control-Expose-Headers: Mcp-Session-Id, MCP-Protocol-Version\r\n")
        append("Access-Control-Max-Age: 86400\r\n")
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        else -> "Error"
    }

    private class SseSession(val out: OutputStream, val socket: Socket) {
        @Synchronized
        fun send(chunk: String) {
            out.write(chunk.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    private companion object {
        const val MAX_BODY = 1 * 1024 * 1024
        const val SSE_PING_MS = 15_000L
    }
}
