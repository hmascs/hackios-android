package com.android.gudao.iphoneos.mcp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** JSON-RPC 层的契约测试：initialize / tools/list / tools/call / notification / 错误码。 */
class McpProtocolTest {

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

    private fun protocol(onTool: (String, Boolean) -> Unit = { _, _ -> }): McpProtocol {
        val dir = tmp.newFolder("run")
        val bridge = Bridge(dir)
        return McpProtocol(ToolBox(bridge, shell = object : ShellRunner {
            override fun run(command: String, cwd: File, env: Map<String, String>, timeoutSec: Int, root: Boolean) =
                ShellResult(0, "ok", false)
        }), onTool)
    }

    private fun McpProtocol.call(text: String): String? = runBlocking { handle(text) }

    @Test
    fun `initialize echoes the requested protocol version`() {
        val p = protocol()
        val body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
        val r = JSONObject(p.call(body)!!)
        assertEquals("2.0", r.getString("jsonrpc"))
        assertEquals(1, r.getInt("id"))
        val result = r.getJSONObject("result")
        assertEquals("2024-11-05", result.getString("protocolVersion"))
        assertEquals(McpConfig.SERVER_NAME, result.getJSONObject("serverInfo").getString("name"))
        assertTrue(result.getJSONObject("capabilities").has("tools"))
        assertTrue(result.getString("instructions").contains("delete_chain"))
    }

    @Test
    fun `initialize falls back to our version for an unknown one`() {
        val p = protocol()
        val r = JSONObject(p.call("""{"jsonrpc":"2.0","id":"a","method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")!!)
        assertEquals("a", r.getString("id"))
        assertEquals(McpConfig.PROTOCOL_VERSION, r.getJSONObject("result").getString("protocolVersion"))
    }

    @Test
    fun `tools list is MCP native format`() {
        val p = protocol()
        val tools = JSONObject(p.call("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!)
            .getJSONObject("result").getJSONArray("tools")
        assertTrue(tools.length() > 20)
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertTrue(names.contains("device_status"))
        assertTrue(names.contains("start_qemu"))
        assertTrue(names.contains("delete_chain"))
        // 每个工具都必须带 inputSchema，客户端靠它拼参数。
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            assertNotNull(t.optJSONObject("inputSchema"))
            assertFalse(t.getString("description").isBlank())
        }
    }

    @Test
    fun `tools call returns MCP content`() {
        val p = protocol()
        val r = JSONObject(p.call("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"device_status","arguments":{}}}""")!!)
            .getJSONObject("result")
        assertFalse(r.getBoolean("isError"))
        assertEquals("text", r.getJSONArray("content").getJSONObject(0).getString("type"))
        assertTrue(r.getJSONArray("content").getJSONObject(0).getString("text").contains("QEMU"))
    }

    @Test
    fun `unknown tool is an isError result not a protocol error`() {
        val p = protocol()
        val r = JSONObject(p.call("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"no_such_tool","arguments":{}}}""")!!)
        assertTrue(r.has("result"))
        assertTrue(r.getJSONObject("result").getBoolean("isError"))
        assertTrue(r.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text").contains("没有这个工具"))
    }

    @Test
    fun `read only tool failure is reported as isError`() {
        val p = protocol()
        val r = JSONObject(p.call("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"read_log","arguments":{"source":"serial"}}}""")!!)
            .getJSONObject("result")
        assertTrue(r.getBoolean("isError"))
    }

    @Test
    fun `notifications produce no response`() {
        val p = protocol()
        assertNull(p.call("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertNull(p.call("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}"""))
        assertNull(p.call("""{"jsonrpc":"2.0","result":{}}"""))
    }

    @Test
    fun `ping and empty capabilities`() {
        val p = protocol()
        val ping = JSONObject(p.call("""{"jsonrpc":"2.0","id":7,"method":"ping"}""")!!)
        assertTrue(ping.has("result"))
        for (m in listOf("resources/list", "resources/templates/list", "prompts/list", "logging/setLevel")) {
            val r = JSONObject(p.call("""{"jsonrpc":"2.0","id":8,"method":"$m"}""")!!)
            assertTrue("$m → $r", r.has("result"))
        }
    }

    @Test
    fun `unknown method returns method not found`() {
        val p = protocol()
        val r = JSONObject(p.call("""{"jsonrpc":"2.0","id":9,"method":"does/not/exist"}""")!!)
        assertEquals(-32601, r.getJSONObject("error").getInt("code"))
        assertEquals(9, r.getInt("id"))
    }

    @Test
    fun `malformed JSON returns parse error`() {
        val p = protocol()
        val r = JSONObject(p.call("{not json")!!)
        assertEquals(-32700, r.getJSONObject("error").getInt("code"))
    }

    @Test
    fun `batch requests keep only real responses`() {
        val p = protocol()
        val body = """[{"jsonrpc":"2.0","method":"notifications/initialized"},{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","id":2,"method":"ping"}]"""
        val text = p.call(body)!!
        assertTrue(text.trim().startsWith("["))
        val arr = org.json.JSONArray(text)
        assertEquals(2, arr.length())
    }

    @Test
    fun `onTool callback reports the tool and outcome`() {
        val seen = mutableListOf<Pair<String, Boolean>>()
        val p = protocol { name, ok -> seen.add(name to ok) }
        p.call("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"device_status","arguments":{}}}""")
        p.call("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"no_such_tool","arguments":{}}}""")
        assertEquals(listOf("device_status" to true, "no_such_tool" to false), seen)
    }

    @Test
    fun `session id is stable and non blank`() {
        val p = protocol()
        assertTrue(p.sessionId.isNotBlank())
        assertEquals(p.sessionId, p.sessionId)
        assertEquals(32, p.sessionId.length)
    }
}
