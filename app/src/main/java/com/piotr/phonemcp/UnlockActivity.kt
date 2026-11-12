package com.piotr.phonemcp

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle

/**
 * Invisible activity started by the `unlock` tool: it turns the screen on and asks the
 * system to show the PIN pad. The service then types the PIN into it.
 */
class UnlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
        // Don't linger over the lock screen if the PIN pad never gets dismissed.
        window.decorView.postDelayed({ finish() }, 20_000)
    }
}
