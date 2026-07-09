package ai.affiora.ope_opaAgent.agent.backend

import ai.affiora.ope_opaAgent.agent.AiProvider
import ai.affiora.ope_opaAgent.agent.ClaudeApiException
import ai.affiora.ope_opaAgent.agent.LocalInferenceEngine
import ai.affiora.ope_opaAgent.agent.LocalModelManager
import ai.affiora.ope_opaAgent.data.model.ClaudeRequest
import ai.affiora.ope_opaAgent.data.model.ClaudeResponse

class LocalBackend(
    private val localInferenceEngine: LocalInferenceEngine,
    private val localModelManager: LocalModelManager,
) : AiBackend {

    /** Register tools for local on-device inference. Called by AgentRuntime which holds the tool registry. */
    fun setTools(toolRegistry: Map<String, ai.affiora.ope_opaAgent.tools.AndroidTool>) {
        localInferenceEngine.setTools(toolRegistry)
    }

    override suspend fun send(
        request: ClaudeRequest,
        apiKey: String,
        provider: AiProvider,
        onTextDelta: ((String) -> Unit)?,
        onThinkingStarted: (() -> Unit)?,
        baseUrlOverride: String?,
    ): ClaudeResponse {
        val modelPath = localModelManager.getModelPath(request.model)
            ?: throw ClaudeApiException(0, "Model '${request.model}' not downloaded. Go to Settings → On-Device Models to download it.")

        if (!localInferenceEngine.isInitialized) {
            localInferenceEngine.initialize(modelPath)
        }

        return localInferenceEngine.generateResponse(
            messages = request.messages,
            systemPrompt = "You are a helpful AI assistant running on the user's Android phone. " +
                "You have tools to control the phone. Use them when the user asks. Be concise.",
            onDelta = onTextDelta,
        )
    }
}
