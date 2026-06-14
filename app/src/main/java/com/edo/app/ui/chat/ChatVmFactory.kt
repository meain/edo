package com.edo.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.edo.app.AppContainer
import com.edo.app.EdoApp

fun chatVmFactory(container: AppContainer, app: EdoApp): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(app, container) as T
    }
}
