package com.android.gudao.iphoneos.mcp

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * MCP 的 JSON-RPC 2.0 层，与传输方式无关（HTTP、SSE 都调它）。
 *
 * 这里只做协议：解析、分发、把 [ToolBox.specs] 原样作为 `tools/list` 的结果、把
 * `tools/call` 的结果包成 MCP 的 `content` 数组。它不碰 socket，也不碰 Android，
 * 所以整条协议链可以在 JVM 上用真实 JSON 端到端地测。
 *
 * 实现的是 MCP 2025-03-26 的 Streamable HTTP 语义，同时接受 2024-11-05 的旧客户端：
 * 旧客户端走 `/sse` + `/message`，新客户端走 `/mcp`，两者共用这一份方法表。
 */
class McpProtocol(
    private val toolbox: ToolBox,
    /** 每次工具调用后回调，供 App 更新「最近调用 / 请求数」。参数：工具名、是否成功。 */
    private val onTool: (String, Boolean) -> Unit = { _, _ -> },
) {

    /** 每个服务实例一个会话号；`initialize` 的响应头会带上它。 */
    val sessionId: String = UUID.randomUUID().toString().filter { it != '-' }

    /** 当前客户端能看到的工具（已经按权限档裁剪过）。 */
    fun toolsList(): JSONArray = toolbox.specs

    /**
     * 处理一个（或一批）JSON-RPC 消息。
     *
     * @return 需要回给客户端的 JSON 文本；返回 null 表示这是 notification / response，
     *         按规范不产生响应体（HTTP 层回 202 Accepted）。
     */
    suspend fun handle(raw: String): String? {
        val body = raw.trim()
        if (body.isEmpty()) return error(null, PARSE_ERROR, "空请求体")
        return try {
            if (body.startsWith("[")) {
                val batch = JSONArray(body)
                val replies = JSONArray()
                for (i in 0 until batch.length()) {
                    val one = dispatchOne(batch.opt(i) ?: JSONObject.NULL)
                    if (one != null) replies.put(JSONObject(one))
                }
                if (replies.length() == 0) null else replies.toString()
            } else {
                dispatchOne(JSONObject(body))
            }
        } catch (e: JSONException) {
            error(null, PARSE_ERROR, "JSON 解析失败：${e.message}")
        } catch (t: Throwable) {
            error(null, INTERNAL_ERROR, "内部错误：${t.javaClass.simpleName} ${t.message.orEmpty()}")
        }
    }

    private suspend fun dispatchOne(message: Any): String? {
        if (message !is JSONObject) return error(null, INVALID_REQUEST, "消息必须是 JSON 对象")
        val method = message.optString("method")
        // 没有 method = 客户端回给我们的 response，或者一个坏消息；都不回复。
        if (method.isEmpty()) return null

        val id: Any? = if (message.has("id")) message.opt("id") else null
        val params = message.optJSONObject("params") ?: JSONObject()

        // JSON-RPC notification：没有 id（或 id 为 null），不回响应。
        if (id == null || id === JSONObject.NULL) {
            onNotification(method)
            return null
        }

        return try {
            when (method) {
                "initialize" -> success(id, initializeResult(params))
                "ping" -> success(id, JSONObject())
                "tools/list" -> success(id, JSONObject().put("tools", toolsList()))
                "tools/call" -> success(id, callTool(params))
                // 声明了空的 resources / prompts 能力：客户端初始化后常会顺手 list 一下，
                // 返回空列表比返回 -32601 更不容易让客户端直接断开。
                "resources/list" -> success(id, JSONObject().put("resources", JSONArray()))
                "resources/templates/list" -> success(id, JSONObject().put("resourceTemplates", JSONArray()))
                "prompts/list" -> success(id, JSONObject().put("prompts", JSONArray()))
                "logging/setLevel" -> success(id, JSONObject())
                "shutdown", "exit" -> success(id, JSONObject())
                else -> error(id, METHOD_NOT_FOUND, "不支持的方法：$method")
            }
        } catch (t: Throwable) {
            error(id, INTERNAL_ERROR, "${t.javaClass.simpleName}: ${t.message.orEmpty()}")
        }
    }

    private fun initializeResult(params: JSONObject): JSONObject {
        val requested = params.optString("protocolVersion")
        // 客户端报的版本我们认识就照它回，不认识就回我们的主版本——SDK 会据此选传输方式。
        val version = if (requested in McpConfig.SUPPORTED_VERSIONS) requested else McpConfig.PROTOCOL_VERSION
        return JSONObject().apply {
            put("protocolVersion", version)
            put(
                "capabilities", JSONObject().apply {
                    put("tools", JSONObject().put("listChanged", false))
                    put("logging", JSONObject())
                    put("resources", JSONObject().put("listChanged", false))
                    put("prompts", JSONObject().put("listChanged", false))
                }
            )
            put(
                "serverInfo", JSONObject().apply {
                    put("name", McpConfig.SERVER_NAME)
                    put("version", McpConfig.SERVER_VERSION)
                }
            )
            put(
                "instructions",
                "HackiOS-on-Android 全链路控制端。整个流程都可用工具管理：下载固件 → 编译镜像 → " +
                    "部署资源 → 启动/停止/重启 QEMU → 取帧与读日志 → 改参数和配置 → 导出日志 → " +
                    "删除整条链路。先调 inspect_chain 看清有哪些产物，改配置用 set_config，删除链路用 " +
                    "delete_chain（默认 dry_run，确认要传 confirm=\"DELETE-CHAIN\"）。" +
                    "破坏性操作需要 设置 → MCP 服务 打开「允许修改与执行」与「完全访问」。"
            )
        }
    }

    /**
     * MCP 规定：工具执行失败仍然是一个成功的 JSON-RPC result，只不过带 `isError: true`，
     * 这样模型能读到失败原因并自己换一种做法。只有参数本身不合法才回 protocol error。
     */
    private suspend fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name")
        if (name.isEmpty()) return toolResult("tools/call 缺少 name 参数", ok = false)
        val args = params.optJSONObject("arguments") ?: JSONObject()
        return try {
            val r = toolbox.execute(name, args.toString())
            onTool(name, r.ok)
            toolResult(r.text, r.ok)
        } catch (t: Throwable) {
            onTool(name, false)
            toolResult("工具 $name 执行异常：${t.javaClass.simpleName} ${t.message.orEmpty()}", ok = false)
        }
    }

    private fun toolResult(text: String, ok: Boolean): JSONObject = JSONObject().apply {
        put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        put("isError", !ok)
    }

    private fun onNotification(method: String) {
        // notifications/initialized 是握手最后一步；其余（cancelled / progress / roots 变化）
        // 目前没有可推送的状态，忽略即可。这里不产生响应。
        if (method == "notifications/initialized") Unit
    }

    private fun success(id: Any?, result: Any): String = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id ?: JSONObject.NULL)
        put("result", result)
    }.toString()

    private fun error(id: Any?, code: Int, message: String): String = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id ?: JSONObject.NULL)
        put(
            "error", JSONObject().apply {
                put("code", code)
                put("message", message)
            }
        )
    }.toString()

    private companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INTERNAL_ERROR = -32603
    }
}
