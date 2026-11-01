package com.piotr.phonemcp

import android.service.notification.NotificationListenerService

/**
 * Bound by the system once the user grants notification access. Only exposes the
 * active notifications; [DeviceController.notifications] does the formatting.
 */
class NotificationReader : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: NotificationReader? = null
            private set
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }
}
