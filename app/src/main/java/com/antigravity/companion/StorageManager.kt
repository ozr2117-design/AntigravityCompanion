package com.antigravity.companion

import android.content.Context
import android.content.SharedPreferences

class StorageManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("antigravity_companion_prefs", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var lastPageUrl: String
        get() = prefs.getString(KEY_LAST_PAGE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_PAGE_URL, value).apply()

    var keepAliveEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEEPALIVE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEPALIVE_ENABLED, value).apply()

    fun isConfigured(): Boolean {
        return serverUrl.isNotBlank()
    }

    companion object {
        private const val KEY_SERVER_URL = "key_server_url"
        private const val KEY_LAST_PAGE_URL = "key_last_page_url"
        private const val KEY_KEEPALIVE_ENABLED = "key_keepalive_enabled"
    }
}
