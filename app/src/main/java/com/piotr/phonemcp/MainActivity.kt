package com.piotr.phonemcp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup screen: service status, endpoint, token and the `claude mcp add` command. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var endpoints: TextView
    private lateinit var token: TextView
    private lateinit var stopButton: Button
    private lateinit var notificationStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, Typeface.BOLD)
        })
        status = label(root, "", 16f)

        button(root, "Open Accessibility Settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        stopButton = button(root, "Stop server (disable service)") {
            PhoneControlService.instance?.disableSelf()
            status.postDelayed({ refresh() }, 500)
        }

        heading(root, "Notifications")
        notificationStatus = label(root, "", 15f)
        button(root, "Grant notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        heading(root, "Endpoint")
        endpoints = label(root, "", 15f, mono = true)

        heading(root, "Token")
        token = label(root, "", 13f, mono = true)

        button(root, "Copy Claude Code command") { copyCommand() }
        button(root, "Regenerate token") {
            AlertDialog.Builder(this)
                .setMessage("Generate a new token? Connected clients will need the new command.")
                .setPositiveButton("Regenerate") { _, _ ->
                    AppSettings.regenerateToken(this)
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        heading(root, "How it works")
        label(root,
            "1. Enable \"Phone MCP\" in Accessibility settings. On Android 13+, if the switch is greyed " +
                "out, open App info → ⋮ → Allow restricted settings first.\n" +
                "2. Copy the command above and run it on the computer with Claude Code. Both devices " +
                "must be on the same network (or use adb forward / Tailscale).\n" +
                "3. Anyone with the token who can reach this phone can control it fully. Regenerate the " +
                "token if it leaks, and stop the server when you're not using it.",
            14f)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val running = PhoneControlService.isServerRunning
        status.text = when {
            running -> "● Server running on port ${AppSettings.PORT}"
            PhoneControlService.lastError != null -> "● Error: ${PhoneControlService.lastError}"
            else -> "○ Not running — enable the accessibility service"
        }
        status.setTextColor(if (running) 0xFF16A34A.toInt() else 0xFFDC2626.toInt())
        stopButton.visibility = if (PhoneControlService.instance != null) View.VISIBLE else View.GONE

        val granted = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, NotificationReader::class.java))
        notificationStatus.text = if (granted) "Claude can read notifications while the phone is unlocked"
        else "Off: Claude can't read notifications"

        val addresses = AppSettings.localAddresses()
        endpoints.text = if (addresses.isEmpty()) "No network connection"
        else addresses.joinToString("\n") { (iface, ip) -> "${AppSettings.endpoint(ip)}  ($iface)" }
        token.text = AppSettings.token(this)
    }

    private fun copyCommand() {
        val host = AppSettings.localAddresses().firstOrNull()?.second ?: "PHONE_IP"
        val command = AppSettings.claudeCommand(host, AppSettings.token(this))
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("claude mcp add", command))
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun heading(parent: LinearLayout, text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
        parent.addView(this)
    }

    private fun label(parent: LinearLayout, text: String, sizeSp: Float, mono: Boolean = false) =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, dp(4))
            if (mono) typeface = Typeface.MONOSPACE
            parent.addView(this)
        }

    private fun button(parent: LinearLayout, text: String, onClick: () -> Unit) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
            parent.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) })
        }
}
