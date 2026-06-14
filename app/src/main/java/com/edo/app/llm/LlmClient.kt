package com.edo.app.llm

import kotlinx.coroutines.flow.Flow

interface LlmClient {
    /**
     * Run one turn against the model: send the conversation, stream events back.
     * Caller assembles tool results and re-invokes for the next turn.
     */
    fun stream(
        system: String?,
        conversation: List<ConvMessage>,
        tools: List<ToolSpec>,
    ): Flow<LlmEvent>
}
