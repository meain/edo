package com.edo.app

import android.app.Application
import com.edo.app.data.ChatDatabase
import com.edo.app.data.EncryptedSettingsStore
import com.edo.app.data.SettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppContainer(app: Application) {
    val settings: SettingsStore = EncryptedSettingsStore(app)
    val db: ChatDatabase = ChatDatabase.get(app)
    val http: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout)
    }

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
}

class EdoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
