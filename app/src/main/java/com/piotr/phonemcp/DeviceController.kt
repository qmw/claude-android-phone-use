package com.piotr.phonemcp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Error that is reported back to the MCP client as a failed tool call. */
class ToolException(message: String) : Exception(message)

/**
 * All device actions. Coordinates exposed to callers are in "model space": the screen
 * scaled so its long edge is at most [MAX_DIMENSION] px — the same space as screenshots.
 */
class DeviceController(private val service: AccessibilityService) {

    companion object {
        const val MAX_DIMENSION = 1280
        private const val MAX_TREE_ELEMENTS = 1500
        private const val MAX_TEXT_LEN = 120
        private const val MAX_NOTIFICATION_LEN = 500
    }

    private val callbackThread = HandlerThread("phone-mcp-callbacks").apply { start() }
    private val handler = Handler(callbackThread.looper)

    /** Elements from the last get_ui_tree call, indexed by their printed number. */
    private var lastElements: List<AccessibilityNodeInfo> = emptyList()

    fun shutdown() {
        callbackThread.quitSafely()
    }

    // ---------------------------------------------------------------- screen geometry

    private fun display(): Display =
        service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    /** Real screen size in physical pixels for the current rotation. */
    fun screenSize(): Point {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display().getRealMetrics(metrics)
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    fun scale(): Double {
        val size = screenSize()
        return min(1.0, MAX_DIMENSION.toDouble() / max(size.x, size.y))
    }

    private fun toModel(v: Int, scale: Double = scale()) = (v * scale).roundToInt()

    /** Model-space point → clamped physical screen point. */
    private fun toScreen(x: Double, y: Double): Pair<Float, Float> {
        val s = scale()
        val size = screenSize()
        val sx = (x / s).coerceIn(0.0, size.x - 1.0)
        val sy = (y / s).coerceIn(0.0, size.y - 1.0)
        return sx.toFloat() to sy.toFloat()
    }

    fun modelSize(): Point {
        val size = screenSize()
        val s = scale()
        return Point(toModel(size.x, s), toModel(size.y, s))
    }

    // ---------------------------------------------------------------- gestures

    private fun dispatch(gesture: GestureDescription, expectedMs: Long) {
        val latch = CountDownLatch(1)
        var completed = false
        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, handler)
        if (!accepted) throw ToolException("The system rejected the gesture")
        if (!latch.await(expectedMs + 3000, TimeUnit.MILLISECONDS)) {
            throw ToolException("Gesture timed out")
        }
        if (!completed) throw ToolException("Gesture was cancelled (another gesture or touch interrupted it)")
    }

    private fun clampDuration(ms: Long) = ms.coerceIn(1, GestureDescription.getMaxGestureDuration())

    fun tap(x: Double, y: Double) = press(x, y, 50)

    fun press(x: Double, y: Double, durationMs: Long) {
        val (sx, sy) = toScreen(x, y)
        val path = Path().apply { moveTo(sx, sy) }
        val duration = clampDuration(durationMs)
        dispatch(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build(), duration)
    }

    fun doubleTap(x: Double, y: Double) {
        val (sx, sy) = toScreen(x, y)
        val path = Path().apply { moveTo(sx, sy) }
        dispatch(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
            .addStroke(GestureDescription.StrokeDescription(path, 140, 40))
            .build(), 200)
    }

    fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, durationMs: Long) {
        val (sx1, sy1) = toScreen(x1, y1)
        val (sx2, sy2) = toScreen(x2, y2)
        val path = Path().apply {
            moveTo(sx1, sy1)
            lineTo(sx2, sy2)
        }
        val duration = clampDuration(durationMs)
        dispatch(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build(), duration)
    }

    /**
     * Scroll so that content in [direction] comes into view ("down" = see what is below,
     * which means the finger moves up). [amount] is the fraction of the screen to travel.
     */
    fun scroll(direction: String, x: Double?, y: Double?, amount: Double) {
        val size = modelSize()
        val cx = x ?: (size.x / 2.0)
        val cy = y ?: (size.y / 2.0)
        val frac = amount.coerceIn(0.1, 0.9)
        val dy = size.y * frac / 2
        val dx = size.x * frac / 2
        when (direction) {
            "down" -> swipe(cx, cy + dy, cx, cy - dy, 400)
            "up" -> swipe(cx, cy - dy, cx, cy + dy, 400)
            "right" -> swipe(cx + dx, cy, cx - dx, cy, 400)
            "left" -> swipe(cx - dx, cy, cx + dx, cy, 400)
            else -> throw ToolException("direction must be up, down, left or right")
        }
    }

    // ---------------------------------------------------------------- global actions

    private val globalActions = mapOf(
        "back" to AccessibilityService.GLOBAL_ACTION_BACK,
        "home" to AccessibilityService.GLOBAL_ACTION_HOME,
        "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
        "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
        "quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
        "power_dialog" to AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
        "lock_screen" to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
        "take_screenshot" to AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT,
    )

    val keyNames: List<String> get() = globalActions.keys.toList()

    fun pressKey(key: String) {
        val action = globalActions[key]
            ?: throw ToolException("Unknown key '$key'. Supported: ${keyNames.joinToString()}")
        if (!service.performGlobalAction(action)) throw ToolException("The system refused '$key'")
    }

    // ---------------------------------------------------------------- screenshot

    class Screenshot(val base64Jpeg: String, val width: Int, val height: Int)

    fun screenshot(quality: Int): Screenshot {
        val bitmap = captureBitmap()
        try {
            val s = scale()
            val w = toModel(bitmap.width, s)
            val h = toModel(bitmap.height, s)
            val scaled = if (w == bitmap.width && h == bitmap.height) bitmap
            else Bitmap.createScaledBitmap(bitmap, w, h, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), out)
            if (scaled !== bitmap) scaled.recycle()
            return Screenshot(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), w, h)
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureBitmap(): Bitmap {
        var lastError = 0
        repeat(4) { attempt ->
            val future = CompletableFuture<Bitmap>()
            service.takeScreenshot(Display.DEFAULT_DISPLAY, { handler.post(it) },
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        buffer.close()
                        if (sw != null) future.complete(sw)
                        else future.completeExceptionally(ToolException("Could not decode screenshot"))
                    }

                    override fun onFailure(errorCode: Int) {
                        future.completeExceptionally(ScreenshotFailure(errorCode))
                    }
                })
            try {
                return future.get(10, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                val cause = e.cause
                if (cause is ScreenshotFailure) {
                    lastError = cause.code
                    // The system allows roughly one screenshot per 333 ms; retry after that.
                    if (cause.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        Thread.sleep(400L * (attempt + 1))
                        return@repeat
                    }
                    throw ToolException(screenshotErrorText(cause.code))
                }
                throw ToolException(cause?.message ?: "Screenshot failed")
            }
        }
        throw ToolException(screenshotErrorText(lastError))
    }

    private class ScreenshotFailure(val code: Int) : Exception()

    private fun screenshotErrorText(code: Int) = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Screenshots requested too quickly"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "Service lacks screenshot permission"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "Invalid display"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "A secure window (FLAG_SECURE) blocks screenshots; use get_ui_tree instead"
        else -> "Screenshot failed (internal error $code)"
    }

    // ---------------------------------------------------------------- UI tree

    fun uiTree(interactiveOnly: Boolean, filter: String?): String {
        val elements = mutableListOf<AccessibilityNodeInfo>()
        val sb = StringBuilder()
        val s = scale()
        val needle = filter?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        var truncated = false

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (elements.size >= MAX_TREE_ELEMENTS) {
                truncated = true
                return
            }
            if (!node.isVisibleToUser) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val text = node.text?.toString()?.oneLine()
            val desc = node.contentDescription?.toString()?.oneLine()
            val hint = node.hintText?.toString()?.oneLine()
            val viewId = node.viewIdResourceName?.substringAfter(":id/")
            val interactive = node.isClickable || node.isLongClickable || node.isEditable ||
                node.isScrollable || node.isCheckable
            val meaningful = interactive || !text.isNullOrEmpty() || !desc.isNullOrEmpty()
            val keep = !rect.isEmpty && (if (interactiveOnly) interactive else meaningful) &&
                (needle == null || listOf(text, desc, hint, viewId).any { it?.lowercase()?.contains(needle) == true })

            var childDepth = depth
            if (keep) {
                val index = elements.size
                elements += node
                val flags = buildList {
                    if (node.isClickable) add("clickable")
                    if (node.isLongClickable) add("long-clickable")
                    if (node.isEditable) add("editable")
                    if (node.isScrollable) add("scrollable")
                    if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                    if (node.isSelected) add("selected")
                    if (node.isFocused) add("focused")
                    if (!node.isEnabled) add("disabled")
                    if (node.isPassword) add("password")
                }
                val indent = if (needle == null && !interactiveOnly) "  ".repeat(depth) else ""
                sb.append(indent).append('[').append(index).append("] ")
                    .append(node.className?.toString()?.substringAfterLast('.') ?: "View")
                if (!text.isNullOrEmpty()) sb.append(" \"").append(text).append('"')
                if (!desc.isNullOrEmpty() && desc != text) sb.append(" desc=\"").append(desc).append('"')
                if (!hint.isNullOrEmpty() && hint != text) sb.append(" hint=\"").append(hint).append('"')
                if (!viewId.isNullOrEmpty()) sb.append(" id=").append(viewId)
                sb.append(" center=(").append(toModel(rect.centerX(), s)).append(',')
                    .append(toModel(rect.centerY(), s)).append(") bounds=[")
                    .append(toModel(rect.left, s)).append(',').append(toModel(rect.top, s)).append("][")
                    .append(toModel(rect.right, s)).append(',').append(toModel(rect.bottom, s)).append(']')
                if (flags.isNotEmpty()) sb.append(' ').append(flags.joinToString(","))
                sb.append('\n')
                childDepth = depth + 1
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it, childDepth) }
            }
        }

        val windows = service.windows.sortedByDescending { it.layer }
        if (windows.isEmpty()) {
            service.rootInActiveWindow?.let { visit(it, 0) }
        }
        for (window in windows) {
            val root = window.root ?: continue
            val headerStart = sb.length
            val elementsBefore = elements.size
            sb.append("## Window: ").append(windowType(window.type))
            window.title?.let { sb.append(" \"").append(it.toString().oneLine()).append('"') }
            root.packageName?.let { sb.append(" (").append(it).append(')') }
            if (window.isActive) sb.append(" [active]")
            sb.append('\n')
            visit(root, 0)
            if (elements.size == elementsBefore) sb.setLength(headerStart)
        }
        lastElements = elements
        if (truncated) sb.append("… truncated at $MAX_TREE_ELEMENTS elements; use filter or interactive_only\n")
        if (elements.isEmpty()) sb.append(if (needle != null) "No elements match '$filter'\n" else "No elements found\n")
        return sb.toString()
    }

    private fun String.oneLine(): String {
        val flat = replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length > MAX_TEXT_LEN) flat.take(MAX_TEXT_LEN) + "…" else flat
    }

    private fun windowType(type: Int) = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "magnification_overlay"
        else -> "type$type"
    }

    fun tapElement(index: Int): String {
        val node = lastElements.getOrNull(index)
            ?: throw ToolException("No element [$index] in the last get_ui_tree result (${lastElements.size} elements). Call get_ui_tree again.")
        if (!node.refresh()) throw ToolException("Element [$index] no longer exists; the screen changed. Call get_ui_tree again.")

        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.isEnabled &&
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            return if (target == node) "Clicked element [$index]"
            else "Clicked the clickable ancestor of element [$index]"
        }
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val s = scale()
        tap(rect.centerX() * s, rect.centerY() * s)
        return "Tapped the center of element [$index] at (${toModel(rect.centerX(), s)},${toModel(rect.centerY(), s)})"
    }

    // ---------------------------------------------------------------- text input

    fun typeText(text: String, clear: Boolean, submit: Boolean): String {
        val node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: throw ToolException("No input field is focused. Tap a text field first.")
        if (!node.isEditable) throw ToolException("The focused element is not editable")

        val existing = if (clear || node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd
        val (newText, cursor) =
            if (!clear && selStart in 0..existing.length && selEnd in selStart..existing.length) {
                existing.substring(0, selStart) + text + existing.substring(selEnd) to selStart + text.length
            } else {
                existing + text to existing.length + text.length
            }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            throw ToolException("The input field refused the text")
        }
        if (!node.isPassword && !textLanded(node, text, newText)) {
            throw ToolException(
                "The field accepted the request but does not show the text; this app may not " +
                    "support accessibility text input. It contains: \"${node.text?.toString()?.oneLine().orEmpty()}\""
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
        if (submit) {
            node.refresh()
            if (!node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                return "Typed the text, but the field has no IME action to submit"
            }
            return "Typed the text and submitted it"
        }
        return "Typed the text"
    }

    /** The field may update asynchronously or reformat the value, e.g. phone numbers. */
    private fun textLanded(node: AccessibilityNodeInfo, typed: String, expected: String): Boolean {
        fun String.bare() = filter { it.isLetterOrDigit() }
        repeat(3) { attempt ->
            if (attempt > 0) Thread.sleep(150)
            node.refresh()
            val actual = node.text?.toString().orEmpty()
            if (actual == expected || actual.bare().contains(typed.bare())) return true
        }
        return false
    }

    // ---------------------------------------------------------------- apps & intents

    private fun isLocked() = service.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun requireUnlocked(action: String) {
        if (isLocked()) throw ToolException("The phone is locked, so $action. Ask the user to unlock it.")
    }

    data class AppInfo(val label: String, val packageName: String)

    fun listApps(filter: String?): List<AppInfo> {
        val pm = service.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val needle = filter?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        @Suppress("DEPRECATION") // the ResolveInfoFlags overload needs API 33
        val activities = pm.queryIntentActivities(intent, 0)
        return activities
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .filter { needle == null || it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle) }
            .sortedBy { it.label.lowercase() }
    }

    fun launchApp(nameOrPackage: String): String {
        requireUnlocked("the app would not appear")
        val pm = service.packageManager
        val pkg = pm.getLaunchIntentForPackage(nameOrPackage)?.let { nameOrPackage }
            ?: listApps(null).let { apps ->
                apps.firstOrNull { it.label.equals(nameOrPackage, ignoreCase = true) }
                    ?: apps.filter { it.label.contains(nameOrPackage, ignoreCase = true) }.singleOrNull()
            }?.packageName
            ?: throw ToolException("No launchable app matches '$nameOrPackage'. Use list_apps to see installed apps.")
        val intent = pm.getLaunchIntentForPackage(pkg)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        service.startActivity(intent)
        return "Launched $pkg"
    }

    fun openUri(uri: String): String {
        requireUnlocked("the link would not open")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            service.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            throw ToolException("No app can open '$uri'")
        }
        return "Opened $uri"
    }

    // ---------------------------------------------------------------- notifications

    fun notifications(appFilter: String?, includeOngoing: Boolean): String {
        requireUnlocked("notifications can't be read")
        val reader = NotificationReader.instance
            ?: throw ToolException("Notification access is not granted. Ask the user to tap " +
                "\"Grant notification access\" in the Phone MCP app.")
        val all = try {
            reader.activeNotifications.orEmpty().toList()
        } catch (e: SecurityException) {
            throw ToolException("Notification access is not available: ${e.message}")
        }
        val pm = service.packageManager
        val needle = appFilter?.trim()?.takeIf { it.isNotEmpty() }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        fun isSummary(n: Notification) = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val groupsWithChildren = all.filterNot { isSummary(it.notification) }.map { it.groupKey }.toSet()

        val lines = all.asSequence()
            .filter { it.packageName != service.packageName }
            .filter { includeOngoing || !it.isOngoing }
            // A group's summary only repeats its children ("3 new messages").
            .filterNot { isSummary(it.notification) && it.groupKey in groupsWithChildren }
            .sortedByDescending { it.postTime }
            .mapNotNull { sbn ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
                } catch (e: Exception) {
                    sbn.packageName
                }
                if (needle != null && !label.contains(needle, ignoreCase = true) &&
                    !sbn.packageName.contains(needle, ignoreCase = true)
                ) return@mapNotNull null
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" | ")
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                buildString {
                    append(time.format(Date(sbn.postTime))).append(" | ").append(label)
                    append(" (").append(sbn.packageName).append(')')
                    if (sbn.isOngoing) append(" [ongoing]")
                    if (!title.isNullOrBlank()) append("\n  ").append(title.oneLine())
                    if (!body.isNullOrBlank()) append("\n  ").append(body.notificationText())
                }
            }
            .toList()
        return if (lines.isEmpty()) "No notifications" else lines.joinToString("\n")
    }

    private fun String.notificationText(): String {
        val flat = replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length > MAX_NOTIFICATION_LEN) flat.take(MAX_NOTIFICATION_LEN) + "…" else flat
    }

    // ---------------------------------------------------------------- device state

    /** Why a screenshot may not show what the caller expects, or null if it should. */
    fun screenWarning(): String? {
        val screenOn = service.getSystemService(PowerManager::class.java).isInteractive
        return when {
            !screenOn && isLocked() -> "The screen is off and the phone is locked, so this image is probably blank or an always-on display."
            !screenOn -> "The screen is off, so this image is probably blank. Use wake_screen."
            isLocked() -> "The phone is locked, so this is the lock screen. Ask the user to unlock it."
            else -> null
        }
    }

    fun foregroundPackage(): String? = service.rootInActiveWindow?.packageName?.toString()

    fun screenInfo(): String {
        val size = screenSize()
        val model = modelSize()
        val power = service.getSystemService(PowerManager::class.java)
        val keyguard = service.getSystemService(KeyguardManager::class.java)
        val rotation = display().rotation * 90
        return buildString {
            appendLine("Coordinate space (use for all tools): ${model.x}x${model.y}")
            appendLine("Physical screen: ${size.x}x${size.y} px, scale ${"%.4f".format(Locale.ROOT, scale())}")
            appendLine("Rotation: $rotation°")
            appendLine("Foreground app: ${foregroundPackage() ?: "unknown"}")
            appendLine("Screen on: ${power.isInteractive}")
            appendLine("Locked: ${keyguard.isKeyguardLocked}" +
                if (keyguard.isKeyguardLocked && keyguard.isDeviceSecure) " (secure lock, needs the user to unlock)" else "")
        }
    }

    fun wakeScreen(): String {
        val power = service.getSystemService(PowerManager::class.java)
        if (power.isInteractive) return "The screen is already on"
        @Suppress("DEPRECATION")
        val lock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "PhoneMCP:wake",
        )
        lock.acquire(3000)
        Thread.sleep(500)
        return if (power.isInteractive) "The screen is on" else "Tried to wake the screen, but it is still off"
    }
}
