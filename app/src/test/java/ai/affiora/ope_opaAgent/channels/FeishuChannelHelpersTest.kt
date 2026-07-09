package ai.affiora.ope_opaAgent.channels

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Unit tests for [FeishuChannel.parseFeishuTextContent] — port of openclaw 38aac70.
 * Empty-text and whitespace-only Feishu messages must be skipped to prevent blank
 * user turns reaching downstream LLMs (MiniMax rejects with "messages must not be
 * empty (2013)").
 */
class FeishuChannelHelpersTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun parseFeishuTextContent_validMessage_returnsText() {
        val body = """{"text":"hello world"}"""
        assertThat(FeishuChannel.parseFeishuTextContent(body, json)).isEqualTo("hello world")
    }

    @Test
    fun parseFeishuTextContent_emptyString_returnsNull() {
        val body = """{"text":""}"""
        // Without the openclaw 38aac70 fix, this returned "" and got dispatched downstream.
        assertThat(FeishuChannel.parseFeishuTextContent(body, json)).isNull()
    }

    @Test
    fun parseFeishuTextContent_whitespaceOnly_returnsNull() {
        val body = """{"text":"   \t\n  "}"""
        assertThat(FeishuChannel.parseFeishuTextContent(body, json)).isNull()
    }

    @Test
    fun parseFeishuTextContent_missingTextKey_returnsNull() {
        val body = """{"other":"x"}"""
        assertThat(FeishuChannel.parseFeishuTextContent(body, json)).isNull()
    }

    @Test
    fun parseFeishuTextContent_invalidJson_returnsNull() {
        val body = """not json at all"""
        assertThat(FeishuChannel.parseFeishuTextContent(body, json)).isNull()
    }

    @Test
    fun parseFeishuTextContent_textWithLeadingWhitespace_preservesContent() {
        // Genuine messages with leading/trailing whitespace are not blank — preserve as-is.
        val body = """{"text":"  hi  "}"""
        assertThat(FeishuChannel.parseFeishuTextContent(body, json)).isEqualTo("  hi  ")
    }
}
