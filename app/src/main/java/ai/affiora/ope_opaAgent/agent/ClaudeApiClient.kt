package ai.affiora.ope_opaAgent.agent

import ai.affiora.ope_opaAgent.agent.backend.AiBackend
import ai.affiora.ope_opaAgent.agent.backend.AnthropicBackend
import ai.affiora.ope_opaAgent.agent.backend.BedrockBackend
import ai.affiora.ope_opaAgent.agent.backend.GoogleBackend
import ai.affiora.ope_opaAgent.agent.backend.LocalBackend
import ai.affiora.ope_opaAgent.agent.backend.OpenAiCompatibleBackend
import ai.affiora.ope_opaAgent.data.model.ClaudeRequest
import ai.affiora.ope_opaAgent.data.model.ClaudeResponse
import ai.affiora.ope_opaAgent.data.prefs.UserPreferences
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

class ClaudeApiException(
    val statusCode: Int,
    val errorBody: String,
) : Exception("API error $statusCode: $errorBody")

@Singleton
class ClaudeApiClient @Inject constructor(
    private val userPreferences: UserPreferences,
    localInferenceEngine: LocalInferenceEngine,
    localModelManager: LocalModelManager,
) {

    private val jsonSerializer = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Ktor client kept only for non-Anthropic providers (OpenAI-compatible, Google, Bedrock). */
    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(jsonSerializer)
        }
        install(Logging) {
            level = LogLevel.INFO
            sanitizeHeader { it == "x-api-key" || it == "Authorization" || it == "x-goog-api-key" }
        }
        // Without this, OkHttp's default 10s readTimeout silently kills any reasoning-
        // model call (DeepSeek R1, MiniMax M2.x, Nemotron Ultra) that takes longer
        // than 10s between packets — surfaced as SocketTimeoutException. Earlier we
        // only installed this on the tool-use HttpClient in ToolsModule; this client
        // is a separate instance that was defaulting to OkHttp's 10s read limit.
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = HttpTimeouts.REQUEST_MS
            connectTimeoutMillis = HttpTimeouts.CONNECT_MS
            socketTimeoutMillis = HttpTimeouts.SOCKET_MS
        }
    }

    // ── Backend instances ──────────────────────────────────────────────────

    private val localBackend = LocalBackend(localInferenceEngine, localModelManager)
    private val anthropicBackend = AnthropicBackend(jsonSerializer)
    private val bedrockBackend = BedrockBackend(
        httpClient = httpClient,
        jsonSerializer = jsonSerializer,
        // openclaw 9189b16: read flag synchronously per request via runBlocking — flag is rarely
        // toggled and reading from DataStore Flow keeps a single source of truth without caching.
        maxThinkingProvider = {
            kotlinx.coroutines.runBlocking { userPreferences.bedrockMaxThinkingEnabled.first() }
        },
    )
    private val googleBackend = GoogleBackend(httpClient, jsonSerializer)
    private val openAiCompatibleBackend = OpenAiCompatibleBackend(httpClient, jsonSerializer)

    companion object {
        private const val TAG = "AiApiClient"
        private const val MAX_RETRIES = 3
        private val RETRYABLE_STATUS_CODES = setOf(429, 503, 529)
    }

    private fun backendFor(provider: AiProvider): AiBackend = when {
        provider.isLocal -> localBackend
        provider.isBedrock -> bedrockBackend
        provider.isAnthropic -> anthropicBackend
        provider == AiProvider.GOOGLE -> googleBackend
        else -> openAiCompatibleBackend
    }

    private suspend fun <T> withRetry(
        onRetry: ((attempt: Int, statusCode: Int, delayMs: Long) -> Unit)? = null,
        block: suspend () -> T,
    ): T {
        var lastException: Exception? = null
        repeat(MAX_RETRIES + 1) { attempt ->
            try {
                return block()
            } catch (e: ClaudeApiException) {
                if (e.statusCode !in RETRYABLE_STATUS_CODES) throw e
                if (attempt >= MAX_RETRIES) throw e
                lastException = e
                val baseDelayMs = 1000L shl attempt
                val jitter = Random.nextLong(0, baseDelayMs / 2)
                val totalDelay = baseDelayMs + jitter
                Log.w(TAG, "Retryable error ${e.statusCode}, attempt ${attempt + 1}/$MAX_RETRIES, backing off ${totalDelay}ms")
                onRetry?.invoke(attempt + 1, e.statusCode, totalDelay)
                delay(totalDelay)
            }
        }
        throw lastException ?: IllegalStateException("Retry exhausted")
    }

    suspend fun sendMessage(
        request: ClaudeRequest,
        onTextDelta: ((String) -> Unit)? = null,
        onThinkingStarted: (() -> Unit)? = null,
        onRetry: ((attempt: Int, statusCode: Int, delayMs: Long) -> Unit)? = null,
    ): ClaudeResponse {
        val primaryProviderId = userPreferences.selectedProvider.first()
        val primaryProvider = AiProvider.fromId(primaryProviderId)

        // Local models bypass failover entirely — failover is for network providers only.
        if (primaryProvider.isLocal) {
            val apiKey = userPreferences.apiKey.first()
            return backendFor(primaryProvider).send(request, apiKey, primaryProvider, onTextDelta, onThinkingStarted)
        }

        // Item D (v1.2.12): build provider chain. Primary always tried first.
        // Failover entries appended only when failoverEnabled flag is on, max 2.
        val failoverEnabled = userPreferences.failoverEnabled.first()
        val failoverChain: List<AiProvider> = if (failoverEnabled) {
            val maxAttempts = userPreferences.failoverMaxAttempts.first().coerceIn(1, 2)
            userPreferences.failoverChain.first()
                .asSequence()
                .filter { it != primaryProviderId } // §12 migration: don't dupe the primary
                .mapNotNull { runCatching { AiProvider.fromId(it) }.getOrNull() } // skip removed providers
                .filter { !it.isLocal } // local doesn't make sense as failover
                .take(maxAttempts)
                .toList()
        } else {
            emptyList()
        }

        val retryPolicy = RetryPolicy(maxFailoverAttempts = failoverChain.size)
        val attempts = mutableListOf<ai.affiora.ope_opaAgent.data.model.AttemptOutcome>()
        val providersToTry = listOf(primaryProvider) + failoverChain

        var lastException: Exception? = null
        for ((index, provider) in providersToTry.withIndex()) {
            // Critical (Codex round 5): models are NOT portable across providers — sending
            // an OpenAI model ID to Anthropic returns deterministic 404. For the primary
            // provider use request.model as-is; for fallback providers, use the fallback's
            // first listed model (best-effort default — user accepts this trade-off when
            // they enable failover, surfaced via actualModelId in response).
            val effectiveModel = if (index == 0) {
                request.model
            } else {
                provider.models.firstOrNull()?.id ?: request.model
            }
            val effectiveRequest = if (effectiveModel == request.model) request else request.copy(model = effectiveModel)

            val attemptStart = System.currentTimeMillis()
            try {
                val response = sendOnce(effectiveRequest, provider, onTextDelta, onThinkingStarted, onRetry)
                val durationMs = System.currentTimeMillis() - attemptStart
                attempts.add(
                    ai.affiora.ope_opaAgent.data.model.AttemptOutcome(
                        providerId = provider.id,
                        modelId = effectiveModel,
                        httpCode = 200,
                        errorBody = null,
                        durationMs = durationMs,
                    )
                )
                if (index > 0) {
                    Log.i(TAG, "failover_succeeded: from=$primaryProviderId to=${provider.id}/$effectiveModel attempt=$index")
                }
                return response.copy(
                    actualProviderId = provider.id,
                    actualModelId = response.model.ifBlank { effectiveModel },
                    attemptTrace = attempts.toList(),
                )
            } catch (e: Exception) {
                val durationMs = System.currentTimeMillis() - attemptStart
                val httpCode = (e as? ClaudeApiException)?.statusCode
                val errorBody = (e as? ClaudeApiException)?.errorBody?.take(500)
                attempts.add(
                    ai.affiora.ope_opaAgent.data.model.AttemptOutcome(
                        providerId = provider.id,
                        modelId = effectiveModel,
                        httpCode = httpCode,
                        errorBody = errorBody,
                        durationMs = durationMs,
                    )
                )
                lastException = e
                val isLast = index == providersToTry.lastIndex
                if (isLast || !retryPolicy.shouldFailover(httpCode, e, index)) {
                    if (index > 0 || failoverEnabled) {
                        Log.e(TAG, "failover_exhausted: attempts=${attempts.size} last=${e.message?.take(120)}")
                    }
                    throw e
                }
                Log.w(TAG, "failover_triggered: from=${provider.id} reason=$httpCode -> next provider")
            }
        }
        throw lastException ?: IllegalStateException("Failover exhausted with no exception")
    }

    private suspend fun sendOnce(
        request: ClaudeRequest,
        provider: AiProvider,
        onTextDelta: ((String) -> Unit)?,
        onThinkingStarted: (() -> Unit)?,
        onRetry: ((Int, Int, Long) -> Unit)?,
    ): ClaudeResponse {
        val apiKey = userPreferences.getTokenForProvider(provider.id)

        val baseUrlOverride: String? = if (provider.requiresCustomBaseUrl) {
            val custom = userPreferences.getBaseUrlForProvider(provider.id)
            if (custom.isBlank()) {
                throw ClaudeApiException(0, "Base URL not configured for ${provider.displayName}. Set it in Settings.")
            }
            custom
        } else {
            null
        }

        if (apiKey.isBlank() && !provider.requiresCustomBaseUrl) {
            throw ClaudeApiException(401, "API key not configured. Go to Settings to add your ${provider.displayName} token.")
        }

        return withRetry(onRetry) {
            backendFor(provider).send(request, apiKey, provider, onTextDelta, onThinkingStarted, baseUrlOverride)
        }
    }

    /** Register tools for local on-device inference. Called by AgentRuntime which holds the tool registry. */
    fun setLocalTools(toolRegistry: Map<String, ai.affiora.ope_opaAgent.tools.AndroidTool>) {
        localBackend.setTools(toolRegistry)
    }

    fun close() {
        httpClient.close()
    }
}
