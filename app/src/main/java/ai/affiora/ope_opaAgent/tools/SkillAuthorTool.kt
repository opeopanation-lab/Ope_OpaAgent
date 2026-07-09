package ai.affiora.ope_opaAgent.tools

import android.content.Context
import ai.affiora.ope_opaAgent.data.prefs.UserPreferences
import ai.affiora.ope_opaAgent.skills.SkillInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

class SkillAuthorTool(
    private val context: Context,
    private val userPreferences: UserPreferences,
    private val skillInstaller: SkillInstaller,
) : AndroidTool {

    companion object {
        private const val USER_SKILLS_DIR = "skills/user"
        private const val SKILL_FILE = "SKILL.md"
    }

    override val name: String = "skills_author"

    override val description: String =
        "Create, read, update, delete, and list user-created skills. Actions: 'list' to list all user skills, 'read' to read a skill, 'create' to create a new skill, 'update' to update a skill, 'delete' to delete a skill."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("list"))
                    add(JsonPrimitive("read"))
                    add(JsonPrimitive("create"))
                    add(JsonPrimitive("update"))
                    add(JsonPrimitive("delete"))
                })
                put("description", JsonPrimitive("The action to perform: 'list', 'read', 'create', 'update', or 'delete'"))
            })
            put("skill_id", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("The skill identifier (required for 'read', 'create', 'update', 'delete')."))
            })
            put("content", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Full SKILL.md content including YAML frontmatter (required for 'create' and 'update')."))
            })
            // Item E (v1.2.12): used by auto-skill follow-up to write a draft skill
            // without confirmation flow. Default "exact" preserves all v1.2.11 behavior
            // (user-confirmed, auto-enabled). "or_suffix" mode is for auto-skill drafts:
            // bypasses confirmation, suffixes on collision, NEVER auto-enabled.
            put("id_strategy", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("exact"))
                    add(JsonPrimitive("or_suffix"))
                })
                put("description", JsonPrimitive("How to handle skill_id: 'exact' (default) requires confirmation and auto-enables; 'or_suffix' is for auto-drafts (no confirmation, suffix on collision, NOT auto-enabled)."))
            })
        })
    }

    private fun getUserSkillsDir(): File {
        return File(context.filesDir, USER_SKILLS_DIR)
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return withContext(Dispatchers.IO) {
            when (action) {
                "list" -> executeList()
                "read" -> executeRead(params)
                "create" -> executeCreate(params)
                "update" -> executeUpdate(params)
                "delete" -> executeDelete(params)
                else -> ToolResult.Error("Unknown action: $action. Must be 'list', 'read', 'create', 'update', or 'delete'.")
            }
        }
    }

    private fun executeList(): ToolResult {
        val skillsDir = getUserSkillsDir()
        if (!skillsDir.exists()) {
            return ToolResult.Success(buildJsonArray {}.toString())
        }

        val skills = buildJsonArray {
            val dirs = skillsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            for (dir in dirs) {
                val skillFile = File(dir, SKILL_FILE)
                if (!skillFile.exists()) continue

                val raw = skillFile.readText()
                val meta = extractFrontmatter(raw)

                add(buildJsonObject {
                    put("id", JsonPrimitive(dir.name))
                    put("name", JsonPrimitive(meta["name"] ?: dir.name))
                    put("description", JsonPrimitive(meta["description"] ?: ""))
                })
            }
        }

        return ToolResult.Success(skills.toString())
    }

    private fun executeRead(params: Map<String, JsonElement>): ToolResult {
        val skillId = params["skill_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: skill_id")

        val skillFile = File(getUserSkillsDir(), "$skillId/$SKILL_FILE")
        if (!skillFile.exists()) {
            return ToolResult.Error("Skill not found: $skillId")
        }

        val content = skillFile.readText()
        val result = buildJsonObject {
            put("skill_id", JsonPrimitive(skillId))
            put("content", JsonPrimitive(content))
        }
        return ToolResult.Success(result.toString())
    }

    private suspend fun executeCreate(params: Map<String, JsonElement>): ToolResult {
        val requestedId = params["skill_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: skill_id")
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: content")
        val idStrategy = params["id_strategy"]?.jsonPrimitive?.content ?: "exact"

        // Item E (v1.2.12): security scan ALWAYS runs, regardless of strategy.
        val scanResult = skillInstaller.scanContent(content)
        if (!scanResult.safe) {
            return ToolResult.Error(
                "Skill content blocked by security scanner: ${scanResult.blockedReasons.joinToString("; ")}"
            )
        }

        if (idStrategy == "or_suffix") {
            // Draft-mode write: bypass confirmation, suffix on collision, do NOT auto-enable.
            // Used by AgentRuntime auto-skill follow-up. User reviews drafts in Skills screen
            // and enables manually — that's the safety boundary (Codex round 3 never #2).
            val resolvedId = resolveIdWithSuffix(requestedId, content)
            val skillDir = File(getUserSkillsDir(), resolvedId)
            skillDir.mkdirs()
            File(skillDir, SKILL_FILE).writeText(content)
            // Boundary §6 ❌: do NOT add to activeSkillIds — auto-drafts must be manually enabled.
            return ToolResult.Success(
                buildJsonObject {
                    put("status", JsonPrimitive("draft_created"))
                    put("requested_id", JsonPrimitive(requestedId))
                    put("actual_id", JsonPrimitive(resolvedId))
                    put("active", JsonPrimitive(false))
                    put("message", JsonPrimitive("Draft skill saved. Open Skills to review and enable."))
                }.toString()
            )
        }

        // id_strategy == "exact" — original v1.2.11 behavior (user-confirmed, auto-enabled).
        val skillDir = File(getUserSkillsDir(), requestedId)
        if (skillDir.exists()) {
            return ToolResult.Error("Skill already exists: $requestedId. Use 'update' to modify it.")
        }

        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (confirmed) {
            skillDir.mkdirs()
            File(skillDir, SKILL_FILE).writeText(content)

            // Auto-enable the newly created skill (manual create only)
            val currentActive = userPreferences.activeSkillIds.first()
            userPreferences.setActiveSkillIds(currentActive + requestedId)

            return ToolResult.Success("Skill '$requestedId' created and auto-enabled successfully.")
        }

        val meta = extractFrontmatter(content)
        val preview = buildString {
            appendLine("Create new skill:")
            appendLine("  ID: $requestedId")
            appendLine("  Name: ${meta["name"] ?: requestedId}")
            appendLine("  Description: ${meta["description"] ?: "(none)"}")
            appendLine("  Tools required: ${meta["tools_required"] ?: "(none)"}")
            appendLine()
            appendLine("Content preview:")
            appendLine(content.take(500))
            if (content.length > 500) appendLine("...[truncated]")
        }

        return ToolResult.NeedsConfirmation(
            preview = preview,
            requestId = UUID.randomUUID().toString(),
        )
    }

    /**
     * Item E (v1.2.12) — deterministic collision resolution. Per Codex round 3 Q2,
     * suffix is a 6-hex-char hash derived from timestamp + content, so the same content
     * regenerated immediately would land at the same suffix (deterministic), but content
     * differences produce different suffixes (avoids accidental overwrites of distinct
     * drafts). Up to 5 retries; if all collide, falls back to timestamp-only suffix.
     */
    private fun resolveIdWithSuffix(requestedId: String, content: String): String {
        val baseDir = getUserSkillsDir()
        if (!File(baseDir, requestedId).exists()) return requestedId
        val seed = (System.currentTimeMillis().toString() + content).hashCode().toUInt()
        for (attempt in 0 until 5) {
            val hash = ((seed + attempt.toUInt()) and 0xFFFFFFu).toString(16).padStart(6, '0')
            val candidate = "$requestedId-$hash"
            if (!File(baseDir, candidate).exists()) return candidate
        }
        return "$requestedId-${System.currentTimeMillis()}"
    }

    private fun executeUpdate(params: Map<String, JsonElement>): ToolResult {
        val skillId = params["skill_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: skill_id")
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: content")

        val skillFile = File(getUserSkillsDir(), "$skillId/$SKILL_FILE")
        if (!skillFile.exists()) {
            return ToolResult.Error("Skill not found: $skillId. Use 'create' to make a new skill.")
        }

        // FIX 7: Security scan updated content before saving
        val scanResult = skillInstaller.scanContent(content)
        if (!scanResult.safe) {
            return ToolResult.Error(
                "Skill content blocked by security scanner: ${scanResult.blockedReasons.joinToString("; ")}"
            )
        }

        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (confirmed) {
            skillFile.writeText(content)
            return ToolResult.Success("Skill '$skillId' updated successfully.")
        }

        val oldContent = skillFile.readText()
        val preview = buildString {
            appendLine("Update skill '$skillId':")
            appendLine()
            appendLine("--- OLD ---")
            appendLine(oldContent.take(300))
            if (oldContent.length > 300) appendLine("...[truncated]")
            appendLine()
            appendLine("--- NEW ---")
            appendLine(content.take(300))
            if (content.length > 300) appendLine("...[truncated]")
        }

        return ToolResult.NeedsConfirmation(
            preview = preview,
            requestId = UUID.randomUUID().toString(),
        )
    }

    private fun executeDelete(params: Map<String, JsonElement>): ToolResult {
        val skillId = params["skill_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: skill_id")

        val skillDir = File(getUserSkillsDir(), skillId)
        if (!skillDir.exists()) {
            return ToolResult.Error("Skill not found: $skillId")
        }

        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (confirmed) {
            skillDir.deleteRecursively()
            return ToolResult.Success("Skill '$skillId' deleted successfully.")
        }

        val skillFile = File(skillDir, SKILL_FILE)
        val meta = if (skillFile.exists()) extractFrontmatter(skillFile.readText()) else emptyMap()

        val preview = buildString {
            appendLine("Delete skill:")
            appendLine("  ID: $skillId")
            appendLine("  Name: ${meta["name"] ?: skillId}")
            appendLine("  Description: ${meta["description"] ?: "(none)"}")
        }

        return ToolResult.NeedsConfirmation(
            preview = preview,
            requestId = UUID.randomUUID().toString(),
        )
    }

    /**
     * Extracts YAML frontmatter fields from a SKILL.md string.
     */
    private fun extractFrontmatter(raw: String): Map<String, String> {
        val trimmed = raw.trimStart()
        if (!trimmed.startsWith("---")) return emptyMap()

        val endIndex = trimmed.indexOf("---", startIndex = 3)
        if (endIndex == -1) return emptyMap()

        val frontmatter = trimmed.substring(3, endIndex).trim()
        val result = mutableMapOf<String, String>()
        for (line in frontmatter.lines()) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty() || !trimmedLine.contains(':')) continue
            val colonIndex = trimmedLine.indexOf(':')
            val key = trimmedLine.substring(0, colonIndex).trim()
            val value = trimmedLine.substring(colonIndex + 1).trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
            if (key.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }
}
