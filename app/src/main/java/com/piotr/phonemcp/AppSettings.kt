package com.piotr.phonemcp

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

object AppSettings {
    const val PORT = 8765
    const val MCP_PATH = "/mcp"
    const val SERVER_NAME = "phone"

    private const val PREFS = "phone_mcp"
    private const val KEY_TOKEN = "token"

    fun token(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TOKEN, null) ?: regenerateToken(context)
    }

    fun regenerateToken(context: Context): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    /** Non-loopback IPv4 addresses of up interfaces, as (interface name, address). */
    fun localAddresses(): List<Pair<String, String>> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { nif.name to it.hostAddress!! }
            }
            // wlan first, then VPN-ish interfaces (tailscale = tun0), then the rest
            .sortedBy { (name, _) ->
                when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("tun") -> 1
                    else -> 2
                }
            }
    } catch (e: Exception) {
        emptyList()
    }

    fun endpoint(host: String) = "http://$host:$PORT$MCP_PATH"

    fun claudeCommand(host: String, token: String) =
        "claude mcp add --transport http $SERVER_NAME ${endpoint(host)} " +
            "--header \"Authorization: Bearer $token\""
}
