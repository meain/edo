package com.edo.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import com.edo.app.data.ChatDatabase
import com.edo.app.data.EncryptedSettingsStore
import com.edo.app.data.SettingsStore
import com.edo.app.schedule.ScheduleManager
import com.edo.app.ui.chat.ChatSessions
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppContainer(private val app: Application) {
    val settings: SettingsStore = EncryptedSettingsStore(app)
    val db: ChatDatabase = ChatDatabase.get(app)
    val http: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout)
    }

    /** Model prices for the cost meter, from LiteLLM's public table. */
    val priceBook = com.edo.app.llm.PriceBook(java.io.File(app.filesDir, "model_prices.json"), http)

    /** Process-wide scope. Used by long-running agent jobs so they survive
     *  ViewModel teardown (e.g., navigating away within the app or brief
     *  configuration churn). It outlives any individual screen. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Per-chat conversations and agent runs; they outlive the screen and the active chat. */
    val chatSessions: ChatSessions = ChatSessions(app, this)

    val scheduler: ScheduleManager = ScheduleManager(app, this)

    /** Text-to-speech for the read-aloud buttons; the engine is only bound on first use. */
    val speaker: com.edo.app.ui.chat.Speaker by lazy { com.edo.app.ui.chat.Speaker(app) }

    /** True while any activity is started; finished-run notifications are only for when it isn't. */
    @Volatile var inForeground = false

    private val _activeProjectId =MutableStateFlow(settings.load().activeProjectId)
    val activeProjectId: StateFlow<Long> = _activeProjectId.asStateFlow()

    private val _activeThreadId = MutableStateFlow(settings.load().activeThreadId)
    val activeThreadId: StateFlow<Long> = _activeThreadId.asStateFlow()

    fun setActiveProject(id: Long) {
        if (_activeProjectId.value == id) return
        _activeProjectId.value = id
        // Reset thread when switching projects — caller can set a thread afterwards
        _activeThreadId.value = -1L
        settings.save(settings.load().copy(activeProjectId = id, activeThreadId = -1L))
    }

    fun setActiveThread(id: Long) {
        if (_activeThreadId.value == id) return
        _activeThreadId.value = id
        settings.save(settings.load().copy(activeThreadId = id))
    }

    /**
     * Workspace-folder picker. The picker stops the activity, which disposes whatever composable
     * launched it, so the launcher lives on the activity and the result is published here for the
     * sheet to consume once it is back.
     */
    var launchFolderPicker: (initial: Uri?) -> Unit = {}
    val pickedFolder = MutableStateFlow<String?>(null)

    /** Drops the persisted folder grant for [uri] unless another project still points at it. */
    suspend fun releaseGrant(uri: String) {
        if (db.projects().observeAll().first().any { it.workspaceUri == uri }) return
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /**
     * Releases folder grants no project points at (deleted projects, a wiped DB, an abandoned
     * pick), so the app only ever holds access to folders that belong to a project.
     */
    suspend fun releaseOrphanGrants() {
        val used = db.projects().observeAll().first().map { it.workspaceUri }.toSet()
        for (perm in app.contentResolver.persistedUriPermissions) {
            if (perm.uri.toString() !in used) releaseGrant(perm.uri.toString())
        }
    }

    private val _pendingShare = MutableStateFlow<SharedContent?>(null)
    /** Content received via the system share sheet, until the chat screen consumes it. */
    val pendingShare: StateFlow<SharedContent?> = _pendingShare.asStateFlow()

    fun offerShare(text: String, uris: List<Uri>) {
        _pendingShare.value = SharedContent(System.nanoTime(), text, uris)
    }

    /** The user picked a destination; the chat screen should now apply the content. */
    fun confirmShare() = _pendingShare.update { it?.copy(confirmed = true) }

    fun clearShare() {
        _pendingShare.value = null
    }
}

/** Shared text and/or file URIs. [confirmed] flips once a project and chat have been chosen. */
data class SharedContent(
    val id: Long,
    val text: String,
    val uris: List<Uri>,
    val confirmed: Boolean = false,
)

class EdoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                started++
                container.inForeground = true
            }
            override fun onActivityStopped(activity: Activity) {
                started--
                container.inForeground = started > 0
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // Make sure the alarm matches the table (e.g. after the system cleared it). Not a
        // resync: if this process was started by the alarm itself, its run must not be skipped.
        container.appScope.launch { container.scheduler.rearm() }
        container.appScope.launch { container.releaseOrphanGrants() }
        container.appScope.launch { container.priceBook.refreshIfStale() }
    }
}
