package ai.affiora.ope_opaAgent.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Wire DTO for tool errors that need structured handling in channels and UI.
 *
 * Currently used by [ai.affiora.ope_opaAgent.tools.CalendarTool] when Android permission
 * is denied — the channel's system prompt instructs the LLM to relay [actionHint] verbatim
 * to the user, so the user gets a Settings-deep-link URL they can tap, not a generic
 * "I can't access your calendar".
 *
 * Carried in [ai.affiora.ope_opaAgent.tools.ToolResult.Error.message] as a JSON string.
 * Channels parse via [parse]; LLMs see the raw JSON and follow the system-prompt instruction
 * to relay [actionHint] field literally.
 *
 * Boundary (per spec §6 ❌): [deepLinkIntent] MUST be a verified-valid Android Settings
 * intent action — never invented paths like `package:...` or arbitrary URIs.
 */
@Serializable
data class StructuredToolError(
    @SerialName("errorType") val errorType: String,
    @SerialName("permission") val permission: String? = null,
    @SerialName("actionHint") val actionHint: String,
    @SerialName("deepLinkIntent") val deepLinkIntent: String? = null,
) {
    fun toJsonString(json: Json = JSON): String = json.encodeToString(this)

    companion object {
        const val ERROR_TYPE_PERMISSION_DENIED = "permission_denied"

        /**
         * Standard Android Settings intent for opening the app's permission detail page.
         * This is `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` plus the package URI scheme;
         * resolves on every Android version since API 9.
         */
        const val SETTINGS_INTENT_APP_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS"

        private val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun parse(jsonString: String): StructuredToolError? = runCatching {
            JSON.decodeFromString<StructuredToolError>(jsonString)
        }.getOrNull()
    }
}
