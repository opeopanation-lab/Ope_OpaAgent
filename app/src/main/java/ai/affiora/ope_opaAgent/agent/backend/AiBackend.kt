package ai.affiora.ope_opaAgent.agent.backend

import ai.affiora.ope_opaAgent.agent.AiProvider
import ai.affiora.ope_opaAgent.data.model.ClaudeRequest
import ai.affiora.ope_opaAgent.data.model.ClaudeResponse

sealed interface AiBackend {
    suspend fun send(
        request: ClaudeRequest,
        apiKey: String,
        provider: AiProvider,
        onTextDelta: ((String) -> Unit)? = null,
        onThinkingStarted: (() -> Unit)? = null,
        /** Optional baseUrl override (used by CUSTOM provider for self-hosted endpoints). */
        baseUrlOverride: String? = null,
    ): ClaudeResponse
}
