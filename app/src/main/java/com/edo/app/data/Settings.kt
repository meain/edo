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
    val maxTurns: Int = 50,
    /** Cheaper model for side tasks (chat titles); blank falls back to [model]. */
    val weakModel: String = "",
)

/** Settings to use for side tasks such as naming a chat: the weak model if one is set. */
fun AppSettings.forSideTasks(): AppSettings =
    if (weakModel.isBlank()) this else copy(model = weakModel.trim())

/** Global settings with the project's model / max-turns overrides applied, if any. */
fun AppSettings.withOverrides(project: ProjectEntity): AppSettings = copy(
    model = project.modelOverride?.takeIf { it.isNotBlank() } ?: model,
    maxTurns = project.maxTurnsOverride?.takeIf { it > 0 } ?: maxTurns,
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
        maxTurns = prefs.getInt(KEY_MAX_TURNS, 50),
        weakModel = prefs.getString(KEY_WEAK_MODEL, "") ?: "",
    )

    override fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_PROVIDER, settings.provider.name)
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_MODEL, settings.model)
            .putLong(KEY_ACTIVE_PROJECT_ID, settings.activeProjectId)
            .putLong(KEY_ACTIVE_THREAD_ID, settings.activeThreadId)
            .putInt(KEY_MAX_TURNS, settings.maxTurns)
            .putString(KEY_WEAK_MODEL, settings.weakModel)
            .apply()
    }

    companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_ACTIVE_PROJECT_ID = "active_project_id"
        const val KEY_ACTIVE_THREAD_ID = "active_thread_id"
        const val KEY_MAX_TURNS = "max_turns"
        const val KEY_WEAK_MODEL = "weak_model"
    }
}

class InMemorySettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    private var state = initial
    override fun load(): AppSettings = state
    override fun save(settings: AppSettings) { state = settings }
}
