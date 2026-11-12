package com.piotr.phonemcp

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest

/**
 * MCP over the Streamable HTTP transport, answering every request with a plain JSON
 * response (no SSE streams, no server-initiated messages).
 */
class McpHttpServer(
    port: Int,
    private val tokenProvider: () -> String,
    private val tools: ToolRegistry,
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "PhoneMCP"
        private const val JSON = "application/json; charset=utf-8"
        private const val MAX_BODY = 1 shl 20
        private val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
    }

    private class RpcError(val code: Int, message: String) : Exception(message)

    override fun serve(session: IHTTPSession): Response {
        // Always consume the body first: an unread body on a keep-alive connection would be
        // parsed as the next request ("Syntax error. HTTP verb {...").
        val body = try {
            readBody(session)
        } catch (e: Exception) {
            return plain(Response.Status.BAD_REQUEST, e.message ?: "Bad request").apply { setKeepAlive(false) }
        }
        val path = session.uri.trimEnd('/')
        if (path != AppSettings.MCP_PATH) {
            return plain(Response.Status.NOT_FOUND, "Not found. The MCP endpoint is ${AppSettings.MCP_PATH}")
        }
        if (!authorized(session)) {
            return plain(Response.Status.UNAUTHORIZED, "Missing or invalid bearer token")
        }
        return when (session.method) {
            Method.POST -> handlePost(body)
            // No standalone SSE stream and no sessions to terminate.
            else -> plain(Response.Status.METHOD_NOT_ALLOWED, "Only POST is supported").also {
                it.addHeader("Allow", "POST")
            }
        }
    }

    private fun authorized(session: IHTTPSession): Boolean {
        val header = session.headers["authorization"] ?: return false
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        val presented = header.substring(7).trim().toByteArray()
        return MessageDigest.isEqual(presented, tokenProvider().toByteArray())
    }

    private fun handlePost(body: String): Response {
        val parsed = try {
            JSONTokener(body).nextValue()
        } catch (e: JSONException) {
            return json(Response.Status.OK, rpcError(JSONObject.NULL, -32700, "Parse error").toString())
        }
        return when (parsed) {
            is JSONObject -> handleMessage(parsed)
                ?.let { json(Response.Status.OK, it.toString()) }
                ?: accepted()
            is JSONArray -> {
                val replies = JSONArray()
                for (i in 0 until parsed.length()) {
                    val msg = parsed.optJSONObject(i)
                    val reply = if (msg == null) rpcError(JSONObject.NULL, -32600, "Invalid request") else handleMessage(msg)
                    reply?.let { replies.put(it) }
                }
                if (replies.length() == 0) accepted() else json(Response.Status.OK, replies.toString())
            }
            else -> json(Response.Status.OK, rpcError(JSONObject.NULL, -32600, "Invalid request").toString())
        }
    }

    /** Returns the JSON-RPC response, or null for notifications and client responses. */
    private fun handleMessage(msg: JSONObject): JSONObject? {
        if (!msg.has("method")) return null // a response to a server request; we never send any
        val method = msg.optString("method")
        val isNotification = !msg.has("id")
        val id = msg.opt("id") ?: JSONObject.NULL
        val params = msg.optJSONObject("params") ?: JSONObject()

        val result = try {
            when (method) {
                "initialize" -> initialize(params)
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", tools.listJson())
                "tools/call" -> callTool(params)
                else -> {
                    if (isNotification) return null // e.g. notifications/initialized
                    throw RpcError(-32601, "Method not found: $method")
                }
            }
        } catch (e: RpcError) {
            return if (isNotification) null else rpcError(id, e.code, e.message ?: "Error")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $method", e)
            return if (isNotification) null else rpcError(id, -32603, "Internal error: ${e.message}")
        }
        return if (isNotification) null
        else JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    }

    private fun initialize(params: JSONObject): JSONObject {
        val requested = params.optString("protocolVersion")
        val version = if (requested in SUPPORTED_VERSIONS) requested else SUPPORTED_VERSIONS.first()
        return JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", "phone-mcp").put("version", "0.3.1"))
            .put("instructions",
                "Controls an Android phone. Typical loop: screenshot or get_ui_tree to see the screen, " +
                    "act (tap_element / tap / type_text / scroll / press_key / launch_app), then look again " +
                    "to verify. If the phone is locked, call unlock. All coordinates are in screenshot pixels. Prefer get_ui_tree + tap_element " +
                    "when elements have text or ids; use screenshots for visual content. Ask the user before " +
                    "sending messages, making purchases, or other irreversible actions.")
    }

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name")
        if (!tools.hasTool(name)) throw RpcError(-32602, "Unknown tool: $name")
        return tools.call(name, params.optJSONObject("arguments") ?: JSONObject())
    }

    /** Reads a Content-Length or chunked body; requests with neither have no body. */
    private fun readBody(session: IHTTPSession): String {
        val input = session.inputStream
        val bytes = if (session.headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val size = readLine(input).substringBefore(';').trim().toInt(16)
                if (size == 0) {
                    while (readLine(input).isNotEmpty()) Unit // trailers
                    break
                }
                if (out.size() + size > MAX_BODY) throw IllegalArgumentException("Body too large")
                out.write(readExactly(input, size))
                readLine(input) // CRLF after each chunk
            }
            out.toByteArray()
        } else {
            val length = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (length > MAX_BODY) throw IllegalArgumentException("Body too large")
            readExactly(input, length)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun readExactly(input: java.io.InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) throw IllegalArgumentException("Body ended early")
            read += n
        }
        return buf
    }

    private fun readLine(input: java.io.InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw IllegalArgumentException("Body ended early")
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 1024) throw IllegalArgumentException("Bad chunk header")
            sb.append(c.toChar())
        }
    }

    private fun rpcError(id: Any, code: Int, message: String) = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun json(status: Response.Status, body: String) = newFixedLengthResponse(status, JSON, body)

    private fun plain(status: Response.Status, body: String) =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    private fun accepted() = newFixedLengthResponse(Response.Status.ACCEPTED, "text/plain", "")
}
