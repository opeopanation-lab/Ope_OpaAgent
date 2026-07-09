package ai.affiora.ope_opaAgent.agent.backend

import ai.affiora.ope_opaAgent.data.model.ClaudeContent
import ai.affiora.ope_opaAgent.data.model.ClaudeMessage
import ai.affiora.ope_opaAgent.data.model.ClaudeRequest
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Unit tests for [BedrockBackend] — port of openclaw 9189b16
 * (Opus 4.7 max thinking via additionalModelRequestFields.output_config.effort=max).
 */
class BedrockBackendTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun makeRequest(model: String): ClaudeRequest = ClaudeRequest(
        model = model,
        messages = listOf(ClaudeMessage("user", ClaudeContent.Text("hi"))),
        system = null,
        maxTokens = 100,
        tools = null,
    )

    private val validBedrockApiKey = """{"access_key":"AKIA...","secret_key":"abc","region":"us-west-2"}"""

    /**
     * Capture-and-respond MockEngine used to assert the outgoing request body. Returns
     * an empty Bedrock-shaped success body so [BedrockBackend.send] returns without throwing.
     */
    private fun captureBackend(
        capturedBody: MutableList<String>,
        maxThinkingProvider: (() -> Boolean)? = null,
    ): BedrockBackend {
        val mockEngine = MockEngine { request ->
            val text = request.body.toString() // body is already a buffer-readable channel
            // Reading the actual bytes:
            val bytes = (request.body as? io.ktor.http.content.ByteArrayContent)?.bytes()
                ?: (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()
                ?: byteArrayOf()
            capturedBody.add(String(bytes, Charsets.UTF_8))
            respond(
                content = ByteReadChannel("""{"output":{"message":{"content":[]}},"stopReason":"end_turn"}"""),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf("application/json")),
            )
        }
        val httpClient = HttpClient(mockEngine)
        return BedrockBackend(
            httpClient = httpClient,
            jsonSerializer = json,
            maxThinkingProvider = maxThinkingProvider,
        )
    }

    @Test
    fun send_withoutMaxThinkingFlag_omitsAdditionalModelRequestFields() {
        val captured = mutableListOf<String>()
        val backend = captureBackend(captured, maxThinkingProvider = { false })

        runBlocking {
            backend.send(
                request = makeRequest("us.anthropic.claude-opus-4-7"),
                apiKey = validBedrockApiKey,
                provider = ai.affiora.ope_opaAgent.agent.AiProvider.BEDROCK,
                onTextDelta = null,
                onThinkingStarted = null,
                baseUrlOverride = null,
            )
        }

        assertThat(captured).hasSize(1)
        val body = json.parseToJsonElement(captured[0]).jsonObject
        assertThat(body.containsKey("additionalModelRequestFields")).isFalse()
    }

    @Test
    fun send_withMaxThinkingFlag_butNonOpus47Model_omitsAdditionalModelRequestFields() {
        // Opus 4.6 / Haiku must NOT receive max_thinking — they don't support it.
        val captured = mutableListOf<String>()
        val backend = captureBackend(captured, maxThinkingProvider = { true })

        runBlocking {
            backend.send(
                request = makeRequest("us.anthropic.claude-opus-4-6"),
                apiKey = validBedrockApiKey,
                provider = ai.affiora.ope_opaAgent.agent.AiProvider.BEDROCK,
                onTextDelta = null,
                onThinkingStarted = null,
                baseUrlOverride = null,
            )
        }

        val body = json.parseToJsonElement(captured[0]).jsonObject
        assertThat(body.containsKey("additionalModelRequestFields")).isFalse()
    }

    @Test
    fun send_withMaxThinkingFlag_andOpus47_includesEffortMax() {
        val captured = mutableListOf<String>()
        val backend = captureBackend(captured, maxThinkingProvider = { true })

        runBlocking {
            backend.send(
                request = makeRequest("us.anthropic.claude-opus-4-7"),
                apiKey = validBedrockApiKey,
                provider = ai.affiora.ope_opaAgent.agent.AiProvider.BEDROCK,
                onTextDelta = null,
                onThinkingStarted = null,
                baseUrlOverride = null,
            )
        }

        val body = json.parseToJsonElement(captured[0]).jsonObject
        val fields = body["additionalModelRequestFields"] as? JsonObject
        assertThat(fields).isNotNull()
        val outputConfig = fields!!["output_config"] as? JsonObject
        assertThat(outputConfig).isNotNull()
        assertThat((outputConfig!!["effort"] as JsonPrimitive).content).isEqualTo("max")
        val thinking = fields["thinking"] as? JsonObject
        assertThat((thinking!!["type"] as JsonPrimitive).content).isEqualTo("adaptive")
    }

    @Test
    fun send_nullMaxThinkingProvider_treatedAsDisabled() {
        val captured = mutableListOf<String>()
        val backend = captureBackend(captured, maxThinkingProvider = null)

        runBlocking {
            backend.send(
                request = makeRequest("us.anthropic.claude-opus-4-7"),
                apiKey = validBedrockApiKey,
                provider = ai.affiora.ope_opaAgent.agent.AiProvider.BEDROCK,
                onTextDelta = null,
                onThinkingStarted = null,
                baseUrlOverride = null,
            )
        }

        val body = json.parseToJsonElement(captured[0]).jsonObject
        assertThat(body.containsKey("additionalModelRequestFields")).isFalse()
    }
}
