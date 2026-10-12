package com.edo.app

import android.app.Application
import android.net.Uri
import com.edo.app.data.ChatDatabase
import com.edo.app.data.EncryptedSettingsStore
import com.edo.app.data.SettingsStore
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
import kotlinx.coroutines.flow.update

class AppContainer(private val app: Application) {
    val settings: SettingsStore = EncryptedSettingsStore(app)
    val db: ChatDatabase = ChatDatabase.get(app)
    val http: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout)
    }

    /** Process-wide scope. Used by long-running agent jobs so they survive
     *  ViewModel teardown (e.g., navigating away within the app or brief
     *  configuration churn). It outlives any individual screen. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Per-chat conversations and agent runs; they outlive the screen and the active chat. */
    val chatSessions: ChatSessions = ChatSessions(app, this)

    private val _activeProjectId = MutableStateFlow(settings.load().activeProjectId)
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
    }
}
