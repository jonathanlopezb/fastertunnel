package com.fastertunnel.app.core.data

import android.content.Context
import android.content.SharedPreferences

class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "fastertunnel_prefs",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_TOKEN = "key_jwt_token"
        private const val KEY_SESSION_ID = "key_session_id"
        private const val KEY_USERNAME = "key_username"
        private const val KEY_PASSWORD = "key_password"
        private const val KEY_REMEMBER_CREDENTIALS = "key_remember"
        private const val KEY_SELECTED_SERVER_ID = "key_selected_server_id"
        private const val KEY_SELECTED_SERVER_HOST = "key_selected_server_host"
        private const val KEY_SELECTED_SERVER_PORT = "key_selected_server_port"
        private const val KEY_SELECTED_SERVER_NAME = "key_selected_server_name"
        private const val KEY_SELECTED_TRANSPORT = "key_selected_transport"
        private const val KEY_SELECTED_MODE = "key_selected_mode"
        private const val KEY_SELECTED_TWEAK_ID = "key_selected_tweak_id"
        private const val KEY_CUSTOM_SNI = "key_custom_sni"
        private const val KEY_IS_LOGGED_IN = "key_is_logged_in"
    }

    var selectedServerHost: String
        get() = prefs.getString(KEY_SELECTED_SERVER_HOST, "3.142.187.253") ?: "3.142.187.253"
        set(value) = prefs.edit().putString(KEY_SELECTED_SERVER_HOST, value).apply()

    var selectedServerPort: Int
        get() = prefs.getInt(KEY_SELECTED_SERVER_PORT, 53)
        set(value) = prefs.edit().putInt(KEY_SELECTED_SERVER_PORT, value).apply()

    var selectedServerName: String
        get() = prefs.getString(KEY_SELECTED_SERVER_NAME, "🔥 AWS Ohio #1 (FastDNS)") ?: "🔥 AWS Ohio #1 (FastDNS)"
        set(value) = prefs.edit().putString(KEY_SELECTED_SERVER_NAME, value).apply()

    var selectedTransport: String
        get() = prefs.getString(KEY_SELECTED_TRANSPORT, "FAST_DNS") ?: "FAST_DNS"
        set(value) = prefs.edit().putString(KEY_SELECTED_TRANSPORT, value).apply()

    var selectedMode: String
        get() = prefs.getString(KEY_SELECTED_MODE, "FastDNS") ?: "FastDNS"
        set(value) = prefs.edit().putString(KEY_SELECTED_MODE, value).apply()

    var customSni: String
        get() = prefs.getString(KEY_CUSTOM_SNI, "web.whatsapp.net") ?: "web.whatsapp.net"
        set(value) = prefs.edit().putString(KEY_CUSTOM_SNI, value).apply()

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var sessionId: String?
        get() = prefs.getString(KEY_SESSION_ID, null)
        set(value) = prefs.edit().putString(KEY_SESSION_ID, value).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    var rememberCredentials: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_CREDENTIALS, true)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER_CREDENTIALS, value).apply()

    var selectedServerId: String
        get() = prefs.getString(KEY_SELECTED_SERVER_ID, "gw-us-01") ?: "gw-us-01"
        set(value) = prefs.edit().putString(KEY_SELECTED_SERVER_ID, value).apply()

    var selectedTweakId: String
        get() = prefs.getString(KEY_SELECTED_TWEAK_ID, "direct_udp") ?: "direct_udp"
        set(value) = prefs.edit().putString(KEY_SELECTED_TWEAK_ID, value).apply()

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()

    fun clearAuth() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_SESSION_ID)
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .apply()
    }
}
