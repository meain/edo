package com.edo.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class Provider {
    Anthropic,
    OpenAI;

    companion object {
        fun fromName(name: String?): Provider = when (name) {
            "OpenAI" -> OpenAI
            else -> Anthropic
        }
    }
}

data class AppSettings(
    val provider: Provider = Provider.Anthropic,
    val baseUrl: String = "https://api.anthropic.com",
    val apiKey: String = "",
    val model: String = "claude-3-5-sonnet-latest",
    val activeProjectId: Long = -1L,
    val activeThreadId: Long = -1L,
    val yoloMode: Boolean = false,
)

interface SettingsStore {
    fun load(): AppSettings
    fun save(settings: AppSettings)
}

class EncryptedSettingsStore(context: Context) : SettingsStore {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "edo-secure-prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): AppSettings = AppSettings(
        provider = Provider.fromName(prefs.getString(KEY_PROVIDER, null)),
        baseUrl = prefs.getString(KEY_BASE_URL, "https://api.anthropic.com") ?: "",
        apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
        model = prefs.getString(KEY_MODEL, "claude-3-5-sonnet-latest") ?: "",
        activeProjectId = prefs.getLong(KEY_ACTIVE_PROJECT_ID, -1L),
        activeThreadId = prefs.getLong(KEY_ACTIVE_THREAD_ID, -1L),
        yoloMode = prefs.getBoolean(KEY_YOLO_MODE, false),
    )

    override fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_PROVIDER, settings.provider.name)
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_MODEL, settings.model)
            .putLong(KEY_ACTIVE_PROJECT_ID, settings.activeProjectId)
            .putLong(KEY_ACTIVE_THREAD_ID, settings.activeThreadId)
            .putBoolean(KEY_YOLO_MODE, settings.yoloMode)
            .apply()
    }

    companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_ACTIVE_PROJECT_ID = "active_project_id"
        const val KEY_ACTIVE_THREAD_ID = "active_thread_id"
        const val KEY_YOLO_MODE = "yolo_mode"
    }
}

class InMemorySettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    private var state = initial
    override fun load(): AppSettings = state
    override fun save(settings: AppSettings) { state = settings }
}
