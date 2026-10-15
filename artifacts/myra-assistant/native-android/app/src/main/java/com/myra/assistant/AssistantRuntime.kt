package com.myra.assistant

import kotlinx.coroutines.flow.MutableStateFlow

object AssistantRuntime {
    val listening = MutableStateFlow(false)
    val status = MutableStateFlow("Assistant stopped")
    val lastEvent = MutableStateFlow("Add a Gemini key to get started.")
}

object ForegroundState {
    @Volatile
    var activityVisible: Boolean = false
}
