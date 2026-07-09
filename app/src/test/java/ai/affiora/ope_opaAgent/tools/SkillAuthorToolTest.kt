package ai.affiora.ope_opaAgent.tools

import ai.affiora.ope_opaAgent.data.prefs.UserPreferences
import ai.affiora.ope_opaAgent.skills.SkillInstaller
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Item E (v1.2.12) tests for [SkillAuthorTool] new id_strategy parameter.
 * Codex round 2 Q2 + spec §7E:
 *   - id_strategy="or_suffix" bypasses confirmation (auto-write draft)
 *   - id_strategy="or_suffix" suffixes on collision (slug + 6-char hash)
 *   - id_strategy="or_suffix" does NOT auto-enable (Codex never #2)
 *   - id_strategy="exact" (default) preserves v1.2.11 behavior
 *   - Security scan still runs in or_suffix mode
 */
class SkillAuthorToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var context: android.content.Context
    private lateinit var userPreferences: UserPreferences
    private lateinit var skillInstaller: SkillInstaller
    private lateinit var tool: SkillAuthorTool

    private val sampleSkill = """
        ---
        name: TestSkill
        description: A test skill
        ---
        Body content here.
    """.trimIndent()

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        every { context.filesDir } returns tempDir.toFile()
        userPreferences = mockk(relaxed = true)
        every { userPreferences.activeSkillIds } returns flowOf(emptySet())
        skillInstaller = mockk()
        every { skillInstaller.scanContent(any()) } returns SkillInstaller.ScanResult(safe = true, blockedReasons = emptyList())
        tool = SkillAuthorTool(context, userPreferences, skillInstaller)
    }

    @AfterEach
    fun cleanup() {
        // tempDir is cleaned automatically by JUnit
    }

    @Test
    fun create_orSuffix_writesDraftWithoutConfirmation() = runBlocking {
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "skill_id" to JsonPrimitive("my-skill"),
                "content" to JsonPrimitive(sampleSkill),
                "id_strategy" to JsonPrimitive("or_suffix"),
            )
        )

        assertThat(result).isInstanceOf(ToolResult.Success::class.java)
        val skillFile = File(tempDir.toFile(), "skills/user/my-skill/SKILL.md")
        assertThat(skillFile.exists()).isTrue()
        assertThat(skillFile.readText()).isEqualTo(sampleSkill)
    }

    @Test
    fun create_orSuffix_doesNotAutoEnable() = runBlocking {
        // Codex never #2: drafts MUST NOT be added to activeSkillIds.
        tool.execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "skill_id" to JsonPrimitive("my-skill"),
                "content" to JsonPrimitive(sampleSkill),
                "id_strategy" to JsonPrimitive("or_suffix"),
            )
        )

        coVerify(exactly = 0) { userPreferences.setActiveSkillIds(any()) }
    }

    @Test
    fun create_orSuffix_appendsSuffixOnCollision() = runBlocking {
        // First create succeeds at exact id.
        tool.execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "skill_id" to JsonPrimitive("dup"),
                "content" to JsonPrimitive(sampleSkill),
                "id_strategy" to JsonPrimitive("or_suffix"),
            )
        )

        // Second create with same id and or_suffix gets a different id.
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "skill_id" to JsonPrimitive("dup"),
                "content" to JsonPrimitive(sampleSkill + "\n# different content"),
                "id_strategy" to JsonPrimitive("or_suffix"),
            )
        )

        assertThat(result).isInstanceOf(ToolResult.Success::class.java)
        val data = (result as ToolResult.Success).data
        // The result is JSON with actual_id field — verify it's NOT just "dup"
        assertThat(data).contains("\"actual_id\":\"dup-")
        // Both directories must exist
        val skillsDir = File(tempDir.toFile(), "skills/user")
        val children = skillsDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        assertThat(children).contains("dup")
        assertThat(children.any { it.startsWith("dup-") }).isTrue()
    }

    @Test
    fun create_exact_default_returnsNeedsConfirmation() = runBlocking {
        // No id_strategy supplied — defaults to "exact", which preserves v1.2.11 behavior.
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "skill_id" to JsonPrimitive("my-skill"),
                "content" to JsonPrimitive(sampleSkill),
            )
        )

        assertThat(result).isInstanceOf(ToolResult.NeedsConfirmation::class.java)
        // File should NOT have been written yet
        assertThat(File(tempDir.toFile(), "skills/user/my-skill/SKILL.md").exists()).isFalse()
    }

    @Test
    fun create_orSuffix_blocksOnSecurityScanFailure() = runBlocking {
        // Codex round 2: security scan must run regardless of strategy.
        every { skillInstaller.scanContent(any()) } returns SkillInstaller.ScanResult(
            safe = false,
            blockedReasons = listOf("malicious pattern detected"),
        )
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "skill_id" to JsonPrimitive("evil"),
                "content" to JsonPrimitive("malicious"),
                "id_strategy" to JsonPrimitive("or_suffix"),
            )
        )

        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("blocked by security scanner")
        assertThat(File(tempDir.toFile(), "skills/user/evil/SKILL.md").exists()).isFalse()
    }

    @Test
    fun create_exact_confirmedFlow_autoEnables() = runBlocking {
        // v1.2.11 behavior preserved: confirmed manual create auto-enables the skill.
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "skill_id" to JsonPrimitive("manual"),
                "content" to JsonPrimitive(sampleSkill),
                "__confirmed" to JsonPrimitive("true"),
            )
        )

        assertThat(result).isInstanceOf(ToolResult.Success::class.java)
        coVerify { userPreferences.setActiveSkillIds(setOf("manual")) }
    }
}
