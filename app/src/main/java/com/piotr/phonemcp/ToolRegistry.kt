package com.piotr.phonemcp

import org.json.JSONArray
import org.json.JSONObject

/** MCP tool definitions and their handlers, backed by a [DeviceController]. */
class ToolRegistry(private val device: DeviceController) {

    private class Tool(
        val name: String,
        val description: String,
        val properties: JSONObject,
        val required: List<String>,
        val handler: (Args) -> List<JSONObject>,
    )

    /** Typed accessors over the tool-call arguments. */
    private class Args(private val json: JSONObject) {
        fun has(key: String) = json.has(key) && !json.isNull(key)
        fun num(key: String): Double {
            if (!has(key)) throw ToolException("Missing argument '$key'")
            return json.optDouble(key).takeUnless { it.isNaN() }
                ?: throw ToolException("Argument '$key' must be a number")
        }
        fun numOr(key: String, default: Double) = if (has(key)) num(key) else default
        fun numOrNull(key: String) = if (has(key)) num(key) else null
        fun str(key: String): String =
            if (has(key)) json.get(key).toString() else throw ToolException("Missing argument '$key'")
        fun strOrNull(key: String) = if (has(key)) json.get(key).toString() else null
        fun bool(key: String, default: Boolean = false) = if (has(key)) json.optBoolean(key, default) else default
    }

    private fun text(s: String) = JSONObject().put("type", "text").put("text", s)

    private fun prop(type: String, description: String, enum: List<String>? = null) =
        JSONObject().put("type", type).put("description", description).apply {
            if (enum != null) put("enum", JSONArray(enum))
        }

    private fun props(vararg entries: Pair<String, JSONObject>) =
        JSONObject().apply { entries.forEach { (k, v) -> put(k, v) } }

    private val xy = arrayOf(
        "x" to prop("number", "X coordinate in screenshot pixels"),
        "y" to prop("number", "Y coordinate in screenshot pixels"),
    )

    private val tools: List<Tool> = listOf(
        Tool(
            "screenshot",
            "Capture the phone screen as a JPEG. Coordinates in the image are the coordinates " +
                "every other tool uses. Call this (or get_ui_tree) after actions to verify the result.",
            props("quality" to prop("integer", "JPEG quality 10-100 (default 60)")),
            emptyList(),
        ) { a ->
            val shot = device.screenshot(a.numOr("quality", 60.0).toInt())
            listOf(
                JSONObject().put("type", "image").put("data", shot.base64Jpeg).put("mimeType", "image/jpeg"),
                text("Screenshot ${shot.width}x${shot.height}. Foreground app: ${device.foregroundPackage() ?: "unknown"}" +
                    (device.screenWarning()?.let { " WARNING: $it" } ?: "")),
            )
        },
        Tool(
            "get_ui_tree",
            "Get the on-screen UI elements of all windows as text: [index] class \"text\" desc=… id=… " +
                "center=(x,y) bounds=[l,t][r,b] flags. More precise and cheaper than a screenshot for " +
                "finding buttons and fields. Use the index with tap_element.",
            props(
                "interactive_only" to prop("boolean", "Only list clickable/editable/scrollable/checkable elements (default false)"),
                "filter" to prop("string", "Only list elements whose text, description, hint or view id contains this (case-insensitive)"),
            ),
            emptyList(),
        ) { a -> listOf(text(device.uiTree(a.bool("interactive_only"), a.strOrNull("filter")))) },
        Tool(
            "tap_element",
            "Click an element by its [index] from the most recent get_ui_tree call. Uses the accessibility " +
                "click action (on the element or its nearest clickable parent), falling back to a tap.",
            props("index" to prop("integer", "Element index from get_ui_tree")),
            listOf("index"),
        ) { a -> listOf(text(device.tapElement(a.num("index").toInt()))) },
        Tool("tap", "Tap the screen at (x, y).", props(*xy), listOf("x", "y")) { a ->
            device.tap(a.num("x"), a.num("y"))
            listOf(text("Tapped (${a.num("x")}, ${a.num("y")})"))
        },
        Tool("double_tap", "Double-tap the screen at (x, y).", props(*xy), listOf("x", "y")) { a ->
            device.doubleTap(a.num("x"), a.num("y"))
            listOf(text("Double-tapped (${a.num("x")}, ${a.num("y")})"))
        },
        Tool(
            "long_press",
            "Long-press at (x, y), e.g. to open context menus or start dragging.",
            props(*xy, "duration_ms" to prop("integer", "Press duration in ms (default 800)")),
            listOf("x", "y"),
        ) { a ->
            device.press(a.num("x"), a.num("y"), a.numOr("duration_ms", 800.0).toLong())
            listOf(text("Long-pressed (${a.num("x")}, ${a.num("y")})"))
        },
        Tool(
            "swipe",
            "Swipe/drag the finger from (x1, y1) to (x2, y2). Slow swipes (>1000 ms) act like drags.",
            props(
                "x1" to prop("number", "Start X"), "y1" to prop("number", "Start Y"),
                "x2" to prop("number", "End X"), "y2" to prop("number", "End Y"),
                "duration_ms" to prop("integer", "Gesture duration in ms (default 300)"),
            ),
            listOf("x1", "y1", "x2", "y2"),
        ) { a ->
            device.swipe(a.num("x1"), a.num("y1"), a.num("x2"), a.num("y2"), a.numOr("duration_ms", 300.0).toLong())
            listOf(text("Swiped"))
        },
        Tool(
            "scroll",
            "Scroll to reveal content in the given direction ('down' shows what is further down the page). " +
                "Optionally scroll a specific area by giving the point to start from.",
            props(
                "direction" to prop("string", "Which content to reveal", listOf("up", "down", "left", "right")),
                "x" to prop("number", "X of the area to scroll (default screen center)"),
                "y" to prop("number", "Y of the area to scroll (default screen center)"),
                "amount" to prop("number", "Fraction of the screen to scroll, 0.1-0.9 (default 0.5)"),
            ),
            listOf("direction"),
        ) { a ->
            device.scroll(a.str("direction"), a.numOrNull("x"), a.numOrNull("y"), a.numOr("amount", 0.5))
            listOf(text("Scrolled ${a.str("direction")}"))
        },
        Tool(
            "type_text",
            "Type text into the currently focused input field (tap the field first). Inserts at the cursor " +
                "unless clear=true. submit=true presses the keyboard's action key (Enter/Search/Send).",
            props(
                "text" to prop("string", "Text to type"),
                "clear" to prop("boolean", "Replace the field's existing text (default false)"),
                "submit" to prop("boolean", "Press the IME action key afterwards (default false)"),
            ),
            listOf("text"),
        ) { a -> listOf(text(device.typeText(a.str("text"), a.bool("clear"), a.bool("submit")))) },
        Tool(
            "press_key",
            "Press a system navigation key / perform a global action.",
            props("key" to prop("string", "Key to press", device.keyNames)),
            listOf("key"),
        ) { a ->
            device.pressKey(a.str("key"))
            listOf(text("Pressed ${a.str("key")}"))
        },
        Tool(
            "list_apps",
            "List installed launchable apps as 'Label — package.name'.",
            props("filter" to prop("string", "Only apps whose label or package contains this")),
            emptyList(),
        ) { a ->
            val apps = device.listApps(a.strOrNull("filter"))
            listOf(text(if (apps.isEmpty()) "No apps found" else apps.joinToString("\n") { "${it.label} — ${it.packageName}" }))
        },
        Tool(
            "launch_app",
            "Launch an app by package name (e.g. com.android.chrome) or by its label (e.g. Chrome).",
            props("app" to prop("string", "Package name or app label")),
            listOf("app"),
        ) { a -> listOf(text(device.launchApp(a.str("app")))) },
        Tool(
            "open_uri",
            "Open a URI with the default handler: web pages (https://…), tel:, mailto:, geo:, sms:, " +
                "market://details?id=…, or app deep links.",
            props("uri" to prop("string", "The URI to open")),
            listOf("uri"),
        ) { a -> listOf(text(device.openUri(a.str("uri")))) },
        Tool(
            "get_notifications",
            "Read active notifications, newest first: time, app, title and text. Works without opening " +
                "the notification shade. Needs notification access and an unlocked phone.",
            props(
                "app" to prop("string", "Only notifications whose app label or package contains this"),
                "include_ongoing" to prop("boolean", "Include ongoing ones such as media or VPN (default false)"),
            ),
            emptyList(),
        ) { a -> listOf(text(device.notifications(a.strOrNull("app"), a.bool("include_ongoing")))) },
        Tool(
            "get_screen_info",
            "Screen size in tool coordinates, rotation, foreground app, whether the screen is on and locked.",
            props(),
            emptyList(),
        ) { listOf(text(device.screenInfo())) },
        Tool(
            "unlock",
            "Wake the phone and unlock it with the PIN/password the user saved in the Phone MCP app " +
                "(it is never revealed). Makes at most one attempt; after a failed attempt remote unlock " +
                "pauses until the user saves the PIN again.",
            props(),
            emptyList(),
        ) { listOf(text(device.unlock())) },
        Tool("wake_screen", "Turn the screen on if it is off.", props(), emptyList()) {
            listOf(text(device.wakeScreen()))
        },
        Tool(
            "wait",
            "Wait for a while, e.g. for an app or page to load.",
            props("ms" to prop("integer", "Milliseconds to wait, max 10000")),
            listOf("ms"),
        ) { a ->
            val ms = a.num("ms").toLong().coerceIn(0, 10_000)
            Thread.sleep(ms)
            listOf(text("Waited $ms ms"))
        },
    )

    private val byName = tools.associateBy { it.name }

    fun listJson(): JSONArray = JSONArray().apply {
        for (t in tools) {
            put(JSONObject()
                .put("name", t.name)
                .put("description", t.description)
                .put("inputSchema", JSONObject()
                    .put("type", "object")
                    .put("properties", t.properties)
                    .put("required", JSONArray(t.required))))
        }
    }

    fun hasTool(name: String) = name in byName

    /** Runs a tool; failures become an `isError` result, as MCP expects for tool errors. */
    fun call(name: String, arguments: JSONObject): JSONObject {
        val tool = byName.getValue(name)
        val content = try {
            // One action at a time: overlapping gestures cancel each other.
            synchronized(this) { tool.handler(Args(arguments)) }
        } catch (e: ToolException) {
            return errorResult(e.message ?: "Error")
        } catch (e: Exception) {
            return errorResult("${e.javaClass.simpleName}: ${e.message}")
        }
        return JSONObject().put("content", JSONArray(content)).put("isError", false)
    }

    private fun errorResult(message: String) =
        JSONObject().put("content", JSONArray().put(text(message))).put("isError", true)
}
