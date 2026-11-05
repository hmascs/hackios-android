package com.android.gudao.iphoneos.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * HTTP 传输层的端到端测试：在 JVM 上起一个真实的 [McpHttpServer]，用 HttpURLConnection 打
 * initialize → tools/list → tools/call，验证 Streamable HTTP、旧版 SSE、鉴权与关闭语义。
 */
class McpHttpServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Bridge(private val dir: File) : QemuBridge {
        override val runDir: File get() = dir
        override val allowWrite: Boolean = true
        override val fullAccess: Boolean = true
        override fun buildLogDir(): File = File(dir, "build-logs")
        override fun chainRoots(): Map<String, File> = mapOf("run" to dir)
        override suspend fun status(): String = "QEMU: 未运行"
        override suspend fun config(): String = "smp=1"
        override suspend fun mcpInfo(): String = "server=hackios-mcp"
        override suspend fun hmp(command: String): String = "HMP:$command"
        override suspend fun screen(): String = "屏幕 1125x2436"
        override suspend fun dispatch(action: String, args: JSONObject): String = "dispatch:$action"
    }

    private val shell = ShellRunner { _, _, _, _, _ -> ShellResult(0, "ok", false) }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun withServer(cfg: McpConfig = McpConfig(), block: (McpHttpServer, Int) -> Unit) {
        val port = freePort()
        val toolbox = ToolBox(Bridge(tmp.newFolder("run")), shell = shell)
        val server = McpHttpServer(McpProtocol(toolbox), config = { cfg.copy(port = port) })
        val state = server.start()
        assertTrue("启动失败：${state.message}", state.ok)
        assertEquals(port, state.port)
        try {
            block(server, port)
        } finally {
            server.stop()
        }
    }

    private fun request(
        port: Int,
        method: String,
        path: String,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
    ): Triple<Int, String, Map<String, List<String>>> {
        val conn = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3000
            readTimeout = 6000
            doOutput = method == "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        if (method == "POST") conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) runCatching { conn.inputStream }.getOrNull() else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        return Triple(code, text, conn.headerFields)
    }

    private fun header(headers: Map<String, List<String>>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    @Test
    fun `health endpoint reports server and tool count`() {
        withServer { _, port ->
            val (code, body, _) = request(port, "GET", "/health")
            assertEquals(200, code)
            val json = JSONObject(body)
            assertEquals(McpConfig.SERVER_NAME, json.getString("server"))
            assertTrue(json.getInt("tools") > 20)
            assertEquals(port, json.getInt("port"))
        }
    }

    @Test
    fun `initialize over POST mcp returns a session header`() {
        withServer { _, port ->
            val (code, body, headers) = request(
                port, "POST", "/mcp",
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
            )
            assertEquals(200, code)
            assertTrue(headers.values.any { it.any { h -> h.contains("application/json") } })
            assertTrue(header(headers, "Mcp-Session-Id")?.isNotBlank() == true)
            assertEquals(McpConfig.PROTOCOL_VERSION, JSONObject(body).getJSONObject("result").getString("protocolVersion"))
        }
    }

    @Test
    fun `full tools list and tools call round trip`() {
        withServer { server, port ->
            val (c1, b1, _) = request(port, "POST", "/mcp", """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            assertEquals(200, c1)
            val tools = JSONObject(b1).getJSONObject("result").getJSONArray("tools")
            assertTrue(tools.length() > 20)

            val (c2, b2, _) = request(
                port, "POST", "/mcp",
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"device_status","arguments":{}}}""",
            )
            assertEquals(200, c2)
            val result = JSONObject(b2).getJSONObject("result")
            assertFalse(result.getBoolean("isError"))
            assertTrue(result.getJSONArray("content").getJSONObject(0).getString("text").contains("QEMU"))
            assertEquals(2, server.requests.get())
            assertEquals("tools/call · device_status", server.lastCall)
        }
    }

    @Test
    fun `notification returns 202 with no body`() {
        withServer { _, port ->
            val (code, body, _) = request(port, "POST", "/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            assertEquals(202, code)
            assertTrue(body.isEmpty())
        }
    }

    @Test
    fun `accept text event stream returns a single SSE message`() {
        withServer { _, port ->
            val (code, body, _) = request(
                port, "POST", "/mcp",
                """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
                headers = mapOf("Accept" to "text/event-stream"),
            )
            assertEquals(200, code)
            assertTrue(body.contains("event: message"))
            assertTrue(body.contains("\"jsonrpc\":\"2.0\""))
        }
    }

    @Test
    fun `options preflight is allowed with CORS headers`() {
        withServer { _, port ->
            val (code, _, headers) = request(port, "OPTIONS", "/mcp")
            assertEquals(204, code)
            assertEquals("*", header(headers, "Access-Control-Allow-Origin"))
            assertTrue(header(headers, "Access-Control-Allow-Headers").orEmpty().contains("Authorization"))
        }
    }

    @Test
    fun `delete mcp closes the session`() {
        withServer { _, port ->
            val (code, _, _) = request(port, "DELETE", "/mcp")
            assertEquals(200, code)
        }
    }

    @Test
    fun `lan mode requires the bearer token`() {
        withServer(McpConfig(bindLan = true, token = "s3cr3t")) { _, port ->
            val (bad, body, _) = request(port, "POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"ping"}""")
            assertEquals(401, bad)
            assertTrue(body.contains("Unauthorized"))
            val (ok, _, _) = request(
                port, "POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
                headers = mapOf("Authorization" to "Bearer s3cr3t"),
            )
            assertEquals(200, ok)
        }
    }

    @Test
    fun `loopback mode needs no token`() {
        withServer(McpConfig(bindLan = false, token = "s3cr3t")) { _, port ->
            val (code, _, _) = request(port, "POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"ping"}""")
            assertEquals(200, code)
        }
    }

    @Test
    fun `legacy sse transport delivers results over the event stream`() {
        withServer { _, port ->
            val socket = Socket("127.0.0.1", port).apply { soTimeout = 6000 }
            socket.use { s ->
                val out = s.getOutputStream()
                out.write(
                    "GET /sse HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                )
                out.flush()
                val reader = s.getInputStream().bufferedReader(Charsets.UTF_8)
                // 跳过响应头
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) line = reader.readLine()
                assertEquals("event: endpoint", reader.readLine())
                val data = reader.readLine() ?: ""
                assertTrue("endpoint=$data", data.startsWith("data: /message?sessionId="))
                reader.readLine() // 空行

                val path = data.removePrefix("data: ")
                // 等服务端把 session 登记好（endpoint 是在登记前写出并 flush 的）。
                Thread.sleep(120)
                val (code, _, _) = request(port, "POST", path, """{"jsonrpc":"2.0","id":42,"method":"ping"}""")
                assertEquals(202, code)
                assertEquals("event: message", reader.readLine())
                val payload = reader.readLine() ?: ""
                assertTrue(payload.startsWith("data: "))
                assertEquals(42, JSONObject(payload.removePrefix("data: ")).getInt("id"))
            }
        }
    }

    @Test
    fun `stop actually closes the listening socket`() {
        val port = freePort()
        val toolbox = ToolBox(Bridge(tmp.newFolder("run")), shell = shell)
        val server = McpHttpServer(McpProtocol(toolbox), config = { McpConfig(port = port) })
        assertTrue(server.start().ok)
        assertTrue(server.running)
        server.stop()
        assertFalse(server.running)
        var refused = false
        try {
            Socket("127.0.0.1", port).close()
        } catch (_: ConnectException) {
            refused = true
        } catch (_: Throwable) {
            refused = true
        }
        assertTrue("stop() 之后端口应不再接受连接", refused)
    }
}
