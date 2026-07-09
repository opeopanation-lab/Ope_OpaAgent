package ai.affiora.ope_opaAgent.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StructuredToolErrorTest {

    @Test
    fun roundTrip_preservesAllFields() {
        val original = StructuredToolError(
            errorType = StructuredToolError.ERROR_TYPE_PERMISSION_DENIED,
            permission = "android.permission.READ_CALENDAR",
            actionHint = "Open Settings → Apps → ope_opaAgent → Permissions → Calendar.",
            deepLinkIntent = StructuredToolError.SETTINGS_INTENT_APP_DETAILS,
        )

        val parsed = StructuredToolError.parse(original.toJsonString())

        assertThat(parsed).isEqualTo(original)
    }

    @Test
    fun deepLinkIntent_isStandardSettingsAction() {
        // Spec §6 ❌ guard: never invent intent paths.
        // android.settings.APPLICATION_DETAILS_SETTINGS resolves on every Android since API 9.
        assertThat(StructuredToolError.SETTINGS_INTENT_APP_DETAILS)
            .isEqualTo("android.settings.APPLICATION_DETAILS_SETTINGS")
    }

    @Test
    fun parse_invalidJson_returnsNull() {
        assertThat(StructuredToolError.parse("not json")).isNull()
    }

    @Test
    fun parse_missingRequiredField_returnsNull() {
        // actionHint is required (no default value).
        assertThat(StructuredToolError.parse("""{"errorType":"x"}""")).isNull()
    }

    @Test
    fun toJsonString_omitsNullFields() {
        val err = StructuredToolError(
            errorType = "x",
            actionHint = "Hint.",
            // permission and deepLinkIntent default to null
        )
        val s = err.toJsonString()
        assertThat(s).doesNotContain("permission")
        assertThat(s).doesNotContain("deepLinkIntent")
    }
}
