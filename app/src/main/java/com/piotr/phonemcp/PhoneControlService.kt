package com.piotr.phonemcp

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility service gives us gestures, screenshots and window content.
 * The MCP server lives exactly as long as this service is enabled.
 */
class PhoneControlService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneMCP"
        private const val CHANNEL_ID = "server"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: PhoneControlService? = null
            private set

        @Volatile
        var lastError: String? = null
            private set

        val isServerRunning: Boolean get() = instance?.server?.isAlive == true
    }

    private var server: McpHttpServer? = null
    private var controller: DeviceController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val device = DeviceController(this).also { controller = it }
        try {
            server = McpHttpServer(AppSettings.PORT, { AppSettings.token(this) }, ToolRegistry(device)).apply {
                // Read timeout for idle keep-alive connections; clients simply reconnect.
                start(60_000, false)
            }
            lastError = null
            showNotification()
            Log.i(TAG, "MCP server listening on port ${AppSettings.PORT}")
        } catch (e: Exception) {
            lastError = "Could not start server on port ${AppSettings.PORT}: ${e.message}"
            Log.e(TAG, lastError, e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        server?.stop()
        server = null
        controller?.shutdown()
        controller = null
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        if (instance === this) instance = null
    }

    private fun showNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Remote control server", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Remote control is active")
            .setContentText("MCP server listening on port ${AppSettings.PORT}")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        // Silently skipped if the user denied the notification permission.
        nm.notify(NOTIFICATION_ID, notification)
    }
}
