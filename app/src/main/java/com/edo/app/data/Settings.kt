package com.edo.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class Provider {
    Anthropic,
    OpenAI,
    OpenRouter;

    companion object {
        fun fromName(name: String?): Provider = when (name) {
            "OpenAI" -> OpenAI
            "OpenRouter" -> OpenRouter
            else -> Anthropic
        }
    }
}

/**
 * OpenRouter routing and request options. Comma-separated fields hold provider slugs
 * (e.g. `deepinfra, together`) or model ids; blank / false means "don't send".
 */
@Serializable
data class OpenRouterOptions(
    /** Only route to these providers (`provider.only`). */
    val onlyProviders: String = "",
    /** Never route to these providers (`provider.ignore`). */
    val ignoreProviders: String = "",
    /** "", "price", "throughput" or "latency" (`provider.sort`). */
    val sort: String = "",
    /** Off = fail instead of falling back to other providers (`provider.allow_fallbacks`). */
    val allowFallbacks: Boolean = true,
    /** Only providers that don't store or train on prompts (`provider.data_collection`). */
    val denyDataCollection: Boolean = false,
    /** Only providers that support every parameter in the request, e.g. tools (`provider.require_parameters`). */
    val requireParameters: Boolean = false,
    /** Only zero-data-retention endpoints (`provider.zdr`). */
    val zeroDataRetention: Boolean = false,
    /** Models to try, in order, if the main one fails (`models`). */
    val fallbackModels: String = "",
    /** "", "low", "medium" or "high" (`reasoning.effort`). */
    val reasoningEffort: String = "",
    /** A JSON object merged into every request body, for anything not covered above. */
    val extraJson: String = "",
)

/** A named, switchable set of connection settings. */
@Serializable
data class ModelProfile(
    val id: Long,
    val name: String,
    val provider: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val weakModel: String = "",
    val openRouter: OpenRouterOptions = OpenRouterOptions(),
)

fun ModelProfile.providerEnum(): Provider = Provider.fromName(provider)

fun AppSettings.toProfile(id: Long, name: String) = ModelProfile(
    id = id, name = name, provider = provider.name, baseUrl = baseUrl, apiKey = apiKey,
    model = model, weakModel = weakModel, openRouter = openRouter,
)

/** These settings with the profile's connection fields applied and the profile marked active. */
fun AppSettings.withProfile(p: ModelProfile) = copy(
    provider = p.providerEnum(), baseUrl = p.baseUrl, apiKey = p.apiKey, model = p.model,
    weakModel = p.weakModel, openRouter = p.openRouter, activeProfileId = p.id,
)

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
    val openRouter: OpenRouterOptions = OpenRouterOptions(),
    /** The profile last applied or saved, or -1 when the settings are ad hoc. */
    val activeProfileId: Long = -1L,
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
    fun loadProfiles(): List<ModelProfile>
    fun saveProfiles(profiles: List<ModelProfile>)
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
        openRouter = prefs.getString(KEY_OPENROUTER, null)
            ?.let { runCatching { Json.decodeFromString<OpenRouterOptions>(it) }.getOrNull() }
            ?: OpenRouterOptions(),
        activeProfileId = prefs.getLong(KEY_ACTIVE_PROFILE_ID, -1L),
    )

    override fun loadProfiles(): List<ModelProfile> = prefs.getString(KEY_PROFILES, null)
        ?.let { runCatching { Json.decodeFromString<List<ModelProfile>>(it) }.getOrNull() }
        ?: emptyList()

    override fun saveProfiles(profiles: List<ModelProfile>) {
        prefs.edit().putString(KEY_PROFILES, Json.encodeToString(profiles)).apply()
    }

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
            .putString(KEY_OPENROUTER, Json.encodeToString(settings.openRouter))
            .putLong(KEY_ACTIVE_PROFILE_ID, settings.activeProfileId)
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
        const val KEY_OPENROUTER = "openrouter_options"
        const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        const val KEY_PROFILES = "model_profiles"
    }
}

class InMemorySettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    private var state = initial
    private var profiles = emptyList<ModelProfile>()
    override fun load(): AppSettings = state
    override fun save(settings: AppSettings) { state = settings }
    override fun loadProfiles(): List<ModelProfile> = profiles
    override fun saveProfiles(profiles: List<ModelProfile>) { this.profiles = profiles }
}
