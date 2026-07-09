package ai.affiora.ope_opaAgent.agent

import ai.affiora.ope_opaAgent.data.model.AgentEvent
import ai.affiora.ope_opaAgent.data.model.ClaudeContent
import ai.affiora.ope_opaAgent.data.model.ClaudeMessage
import ai.affiora.ope_opaAgent.data.model.ClaudeRequest
import ai.affiora.ope_opaAgent.data.model.ClaudeResponse
import ai.affiora.ope_opaAgent.data.model.ClaudeTool
import ai.affiora.ope_opaAgent.data.model.ContentBlock
import ai.affiora.ope_opaAgent.data.model.ImageSource
import ai.affiora.ope_opaAgent.data.prefs.UserPreferences
import ai.affiora.ope_opaAgent.tools.AndroidTool
import ai.affiora.ope_opaAgent.tools.ToolResult
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRuntime @Inject constructor(
    private val apiClient: ClaudeApiClient,
    private val toolRegistry: Map<String, @JvmSuppressWildcards AndroidTool>,
    private val userPreferences: UserPreferences,
    private val permissionManager: PermissionManager,
) {

    private val pendingConfirmations = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    init {
        // Pass tool registry to API client for local on-device inference
        apiClient.setLocalTools(toolRegistry)
    }

    fun run(
        userMessage: String,
        conversationHistory: List<ClaudeMessage>,
        systemPrompt: String,
        imageBase64: String? = null,
    ): Flow<AgentEvent> = flow {
        val model = userPreferences.selectedModel.first()

        val claudeTools = toolRegistry.values.map { tool ->
            ClaudeTool(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.parameters,
            )
        }

        val messages = conversationHistory.toMutableList()
        val userContent = if (imageBase64 != null) {
            ClaudeContent.ContentList(
                blocks = listOf(
                    ContentBlock.ImageBlock(
                        source = ImageSource(
                            mediaType = "image/jpeg",
                            data = imageBase64,
                        ),
                    ),
                    ContentBlock.TextBlock(text = userMessage),
                ),
            )
        } else {
            ClaudeContent.Text(userMessage)
        }
        messages.add(
            ClaudeMessage(
                role = "user",
                content = userContent,
            )
        )

        // Context length warning — server-side compaction handles actual trimming
        val historyChars = messages.sumOf { msg ->
            when (val c = msg.content) {
                is ClaudeContent.Text -> c.text.length
                else -> 500 // rough estimate for non-text
            }
        }
        when {
            historyChars > 200_000 -> emit(AgentEvent.Error("Approaching context limit. Use /compact or start a new conversation."))
            historyChars > 120_000 -> emit(AgentEvent.Error("Conversation getting long (~${historyChars / 4000}K tokens). Consider using /compact."))
        }

        var iterations = 0
        var consecutiveToolErrors = 0
        // Item E (v1.2.12) auto-skill trigger inputs (collected across loop iterations)
        val usedToolNames = mutableSetOf<String>()
        var successfulToolCount = 0
        var hadRejectedConfirmation = false

        while (iterations < MAX_ITERATIONS) {
            iterations++

            val request = ClaudeRequest(
                model = model,
                messages = messages,
                system = systemPrompt,
                tools = claudeTools.ifEmpty { null },
                maxTokens = MAX_TOKENS,
            )

            val response = try {
                callApiStreaming(request)
            } catch (e: ClaudeApiException) {
                emit(AgentEvent.Error(formatApiError(e)))
                return@flow
            } catch (e: java.io.IOException) {
                // UnknownHostException / ConnectException / SocketTimeoutException all
                // extend IOException; formatNetworkError dispatches to the right hint.
                emit(AgentEvent.Error(formatNetworkError(e)))
                return@flow
            } catch (e: Exception) {
                emit(AgentEvent.Error("Unexpected error: ${e.message ?: "unknown"}"))
                return@flow
            }

            // Emit usage stats
            if (response.inputTokens > 0 || response.outputTokens > 0) {
                emit(AgentEvent.Usage(
                    inputTokens = response.inputTokens,
                    outputTokens = response.outputTokens,
                    model = model,
                ))
            }

            // Emit thinking text if present (full thinking for DB persistence)
            if (!response.thinkingText.isNullOrEmpty()) {
                emit(AgentEvent.Thinking(response.thinkingText))
            }

            val toolResultBlocks = mutableListOf<ContentBlock>()
            var hasToolUse = false

            for (block in response.content) {
                when (block) {
                    is ContentBlock.TextBlock -> {
                        // Text deltas were already emitted in real-time via streaming.
                        // Emit the full Text event for DB persistence.
                        emit(AgentEvent.Text(block.text))
                    }
                    is ContentBlock.ToolResultBlock -> {
                        // Server-echoed tool result — skip, we already processed it
                    }
                    is ContentBlock.ImageBlock -> {
                        // Server-echoed image block — skip
                    }
                    is ContentBlock.ToolUseBlock -> {
                        hasToolUse = true
                        usedToolNames.add(block.name) // Item E trigger tracking
                        val inputMap: Map<String, JsonElement> = block.input.toMap()
                            .filterKeys { it != "__confirmed" }  // STRIP — AI cannot set this
                        Log.d("AgentRuntime", "Tool call: ${block.name}")

                        emit(AgentEvent.ToolCalling(toolName = block.name, input = inputMap))

                        val tool = toolRegistry[block.name]
                        val rawResult: String

                        if (tool == null) {
                            rawResult = "Error: Unknown tool '${block.name}'"
                            emit(AgentEvent.ToolResultEvent(
                                toolName = block.name,
                                result = ToolResult.Error("Unknown tool: ${block.name}"),
                            ))
                            consecutiveToolErrors++
                        } else {
                            rawResult = executeAndEmit(tool, block.name, inputMap)
                            if (rawResult.startsWith("Error:") && !rawResult.contains("cancelled by user")) {
                                consecutiveToolErrors++
                            } else {
                                consecutiveToolErrors = 0
                            }
                            // Item E trigger tracking — count successful results, flag rejections
                            if (rawResult.startsWith("Action cancelled by user")) {
                                hadRejectedConfirmation = true
                            } else if (!rawResult.startsWith("Error:")) {
                                successfulToolCount++
                            }
                        }

                        // FIX 6: Cap tool result size to prevent context overflow
                        val maxResultSize = 100_000 // 100KB
                        val resultContent = if (rawResult.length > maxResultSize) {
                            rawResult.take(maxResultSize) + "\n[Truncated: ${rawResult.length} chars total]"
                        } else {
                            rawResult
                        }

                        // Bail out if tools keep failing
                        if (consecutiveToolErrors >= 5) {
                            emit(AgentEvent.Error("Tools failed 5 times in a row. Stopping to prevent infinite loop."))
                            return@flow
                        }

                        toolResultBlocks.add(
                            ContentBlock.ToolResultBlock(
                                toolUseId = block.id,
                                content = resultContent,
                            )
                        )
                    }
                }
            }

            // Add assistant response
            val assistantMessage = ClaudeMessage(
                role = "assistant",
                content = ClaudeContent.ContentList(response.content),
            )
            messages.add(assistantMessage)
            emit(AgentEvent.RawAssistantTurn(assistantMessage))

            // Combine all tool results into a single user message (API requirement)
            if (toolResultBlocks.isNotEmpty()) {
                val toolResultMessage = ClaudeMessage(
                    role = "user",
                    content = ClaudeContent.ContentList(toolResultBlocks),
                )
                messages.add(toolResultMessage)
                emit(AgentEvent.RawToolResultTurn(toolResultMessage))
            }

            if (response.stopReason == "end_turn" || !hasToolUse) {
                // Item E (v1.2.12): on natural turn completion, evaluate auto-skill trigger.
                // The follow-up runs OUTSIDE the agent flow (no AgentEvent emission); failures
                // are logged and never block the user-facing reply.
                runCatching {
                    maybeRunSkillFollowup(
                        conversationContext = messages.toList(),
                        usedToolNames = usedToolNames.toSet(),
                        successfulToolCount = successfulToolCount,
                        hadRejectedConfirmation = hadRejectedConfirmation,
                    )
                }.onFailure { Log.w("AgentRuntime", "autoskill_followup_failed: ${it.message}") }
                break
            }
        }

        if (iterations >= MAX_ITERATIONS) {
            emit(AgentEvent.Error("Agent reached maximum iteration limit ($MAX_ITERATIONS)"))
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.executeAndEmit(
        tool: AndroidTool,
        toolName: String,
        inputMap: Map<String, JsonElement>,
    ): String {
        val result = try {
            tool.execute(inputMap)
        } catch (e: Exception) {
            ToolResult.Error("Tool execution failed: ${e.message ?: "unknown"}")
        }
        Log.d("AgentRuntime", "Tool $toolName result: $result")

        return handleResult(result, tool, toolName, inputMap)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.handleResult(
        result: ToolResult,
        tool: AndroidTool,
        toolName: String,
        inputMap: Map<String, JsonElement>,
    ): String {
        return when (result) {
            is ToolResult.Success -> {
                emit(AgentEvent.ToolResultEvent(toolName = toolName, result = result))
                result.data
            }
            is ToolResult.Error -> {
                emit(AgentEvent.ToolResultEvent(toolName = toolName, result = result))
                "Error: ${result.message}"
            }
            is ToolResult.NeedsConfirmation -> {
                val autoApproved = permissionManager.shouldAutoApprove(toolName)

                if (!autoApproved) {
                    val deferred = CompletableDeferred<Boolean>()
                    pendingConfirmations[result.requestId] = deferred

                    emit(AgentEvent.NeedsConfirmation(
                        toolName = toolName,
                        preview = result.preview,
                        requestId = result.requestId,
                    ))

                    val confirmed = try {
                        deferred.await()
                    } finally {
                        pendingConfirmations.remove(result.requestId)
                    }

                    if (!confirmed) {
                        val cancelResult = ToolResult.Error("Action cancelled by user")
                        emit(AgentEvent.ToolResultEvent(toolName = toolName, result = cancelResult))
                        return "Action cancelled by user"
                    }
                } else {
                    Log.d("AgentRuntime", "Auto-approved tool: $toolName (mode=${permissionManager.mode.value})")
                }

                val confirmedParams = inputMap.toMutableMap()
                confirmedParams["__confirmed"] = JsonPrimitive(true)

                val confirmedResult = try {
                    tool.execute(confirmedParams)
                } catch (e: Exception) {
                    ToolResult.Error("Confirmed execution failed: ${e.message ?: "unknown"}")
                }

                when (confirmedResult) {
                    is ToolResult.Success -> {
                        emit(AgentEvent.ToolResultEvent(toolName = toolName, result = confirmedResult))
                        confirmedResult.data
                    }
                    is ToolResult.Error -> {
                        emit(AgentEvent.ToolResultEvent(toolName = toolName, result = confirmedResult))
                        "Error: ${confirmedResult.message}"
                    }
                    is ToolResult.NeedsConfirmation -> {
                        val errResult = ToolResult.Error("Tool requested confirmation again after being confirmed")
                        emit(AgentEvent.ToolResultEvent(toolName = toolName, result = errResult))
                        "Error: Tool requested confirmation again after being confirmed"
                    }
                }
            }
        }
    }

    /**
     * Run with pre-built conversation history (supports image content blocks).
     * The history must already include the final user message.
     */
    fun runWithHistory(
        conversationHistory: List<ClaudeMessage>,
        systemPrompt: String,
    ): Flow<AgentEvent> = flow {
        val model = userPreferences.selectedModel.first()

        val claudeTools = toolRegistry.values.map { tool ->
            ClaudeTool(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.parameters,
            )
        }

        val messages = conversationHistory.toMutableList()

        // Context length warning — server-side compaction handles actual trimming
        val historyChars = messages.sumOf { msg ->
            when (val c = msg.content) {
                is ClaudeContent.Text -> c.text.length
                else -> 500 // rough estimate for non-text
            }
        }
        when {
            historyChars > 200_000 -> emit(AgentEvent.Error("Approaching context limit. Use /compact or start a new conversation."))
            historyChars > 120_000 -> emit(AgentEvent.Error("Conversation getting long (~${historyChars / 4000}K tokens). Consider using /compact."))
        }

        var iterations = 0
        var consecutiveToolErrors = 0
        // Item E (v1.2.12) auto-skill trigger inputs (collected across loop iterations)
        val usedToolNames = mutableSetOf<String>()
        var successfulToolCount = 0
        var hadRejectedConfirmation = false

        while (iterations < MAX_ITERATIONS) {
            iterations++

            val request = ClaudeRequest(
                model = model,
                messages = messages,
                system = systemPrompt,
                tools = claudeTools.ifEmpty { null },
                maxTokens = MAX_TOKENS,
            )

            val response = try {
                callApiStreaming(request)
            } catch (e: ClaudeApiException) {
                emit(AgentEvent.Error(formatApiError(e)))
                return@flow
            } catch (e: java.io.IOException) {
                // UnknownHostException / ConnectException / SocketTimeoutException all
                // extend IOException; formatNetworkError dispatches to the right hint.
                emit(AgentEvent.Error(formatNetworkError(e)))
                return@flow
            } catch (e: Exception) {
                emit(AgentEvent.Error("Unexpected error: ${e.message ?: "unknown"}"))
                return@flow
            }

            if (response.inputTokens > 0 || response.outputTokens > 0) {
                emit(AgentEvent.Usage(
                    inputTokens = response.inputTokens,
                    outputTokens = response.outputTokens,
                    model = model,
                ))
            }

            // Emit thinking text if present (full thinking for DB persistence)
            if (!response.thinkingText.isNullOrEmpty()) {
                emit(AgentEvent.Thinking(response.thinkingText))
            }

            val toolResultBlocks = mutableListOf<ContentBlock>()
            var hasToolUse = false

            for (block in response.content) {
                when (block) {
                    is ContentBlock.TextBlock -> {
                        // Text deltas were already emitted in real-time via streaming.
                        emit(AgentEvent.Text(block.text))
                    }
                    is ContentBlock.ToolResultBlock -> { /* skip */ }
                    is ContentBlock.ImageBlock -> { /* skip echoed image blocks */ }
                    is ContentBlock.ToolUseBlock -> {
                        hasToolUse = true
                        val inputMap: Map<String, JsonElement> = block.input.toMap()
                            .filterKeys { it != "__confirmed" }  // STRIP — AI cannot set this
                        emit(AgentEvent.ToolCalling(toolName = block.name, input = inputMap))

                        val tool = toolRegistry[block.name]
                        val rawResult: String

                        if (tool == null) {
                            rawResult = "Error: Unknown tool '${block.name}'"
                            emit(AgentEvent.ToolResultEvent(
                                toolName = block.name,
                                result = ToolResult.Error("Unknown tool: ${block.name}"),
                            ))
                            consecutiveToolErrors++
                        } else {
                            rawResult = executeAndEmit(tool, block.name, inputMap)
                            if (rawResult.startsWith("Error:")) {
                                consecutiveToolErrors++
                            } else {
                                consecutiveToolErrors = 0
                            }
                        }

                        // FIX 6: Cap tool result size to prevent context overflow
                        val maxResultSize = 100_000 // 100KB
                        val resultContent = if (rawResult.length > maxResultSize) {
                            rawResult.take(maxResultSize) + "\n[Truncated: ${rawResult.length} chars total]"
                        } else {
                            rawResult
                        }

                        if (consecutiveToolErrors >= 3) {
                            emit(AgentEvent.Error("Tools failed 3 times in a row. Stopping to prevent infinite loop."))
                            return@flow
                        }

                        toolResultBlocks.add(
                            ContentBlock.ToolResultBlock(
                                toolUseId = block.id,
                                content = resultContent,
                            )
                        )
                    }
                }
            }

            val assistantMessage = ClaudeMessage(
                role = "assistant",
                content = ClaudeContent.ContentList(response.content),
            )
            messages.add(assistantMessage)
            emit(AgentEvent.RawAssistantTurn(assistantMessage))

            if (toolResultBlocks.isNotEmpty()) {
                val toolResultMessage = ClaudeMessage(
                    role = "user",
                    content = ClaudeContent.ContentList(toolResultBlocks),
                )
                messages.add(toolResultMessage)
                emit(AgentEvent.RawToolResultTurn(toolResultMessage))
            }

            if (response.stopReason == "end_turn" || !hasToolUse) {
                // Item E (v1.2.12): on natural turn completion, evaluate auto-skill trigger.
                // The follow-up runs OUTSIDE the agent flow (no AgentEvent emission); failures
                // are logged and never block the user-facing reply.
                runCatching {
                    maybeRunSkillFollowup(
                        conversationContext = messages.toList(),
                        usedToolNames = usedToolNames.toSet(),
                        successfulToolCount = successfulToolCount,
                        hadRejectedConfirmation = hadRejectedConfirmation,
                    )
                }.onFailure { Log.w("AgentRuntime", "autoskill_followup_failed: ${it.message}") }
                break
            }
        }

        if (iterations >= MAX_ITERATIONS) {
            emit(AgentEvent.Error("Agent reached maximum iteration limit ($MAX_ITERATIONS)"))
        }
    }

    /**
     * Call the API with real-time streaming: text deltas and thinking events are
     * emitted to the flow as they arrive from the SSE stream, instead of waiting
     * for the full response and then fake-chunking.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.callApiStreaming(
        request: ClaudeRequest,
    ): ClaudeResponse {
        // Channel bridges the IO-thread callbacks into this coroutine's flow context
        val deltaChannel = Channel<AgentEvent>(Channel.BUFFERED)

        return coroutineScope {
            val apiJob = launch {
                try {
                    val response = apiClient.sendMessage(
                        request,
                        onTextDelta = { text ->
                            deltaChannel.trySend(AgentEvent.TextDelta(text))
                        },
                        onThinkingStarted = {
                            deltaChannel.trySend(AgentEvent.Thinking(""))
                        },
                        onRetry = { attempt, statusCode, delayMs ->
                            val delaySec = delayMs / 1000
                            deltaChannel.trySend(AgentEvent.Error("Retrying (attempt $attempt) after error $statusCode — waiting ${delaySec}s..."))
                        },
                    )
                    // Signal completion by sending the response through the channel
                    deltaChannel.trySend(AgentEvent.StreamComplete(response))
                    deltaChannel.close()
                } catch (e: Exception) {
                    deltaChannel.close(e)
                }
            }

            var response: ClaudeResponse? = null
            for (event in deltaChannel) {
                when (event) {
                    is AgentEvent.StreamComplete -> {
                        response = event.response
                    }
                    else -> emit(event)
                }
            }

            // If channel was closed with an exception, the apiJob will throw
            apiJob.join()

            response ?: throw IllegalStateException("Stream ended without a response")
        }
    }

    fun confirmAction(requestId: String, confirmed: Boolean) {
        pendingConfirmations[requestId]?.complete(confirmed)
    }

    fun getToolNames(): List<String> = toolRegistry.keys.sorted()

    // ── Item E (v1.2.12): Auto-skill follow-up ────────────────────────────────

    /**
     * Hidden follow-up pass that asks the LLM to draft a reusable skill from a completed
     * conversation. Called from [run]/[runWithHistory] after `stop_reason==end_turn`.
     *
     * Trigger conditions (all must hold):
     *   - autoSkillMode != Off  (and AutoOnRemote skips local providers)
     *   - successfulToolCount >= 2 (count of successful results, not call count)
     *   - distinct used tool names >= 2 (workflow signal — Codex round 2 granularity)
     *   - "skills_author" not in usedToolNames (recursion guard + already-doing-it skip)
     *   - !hadRejectedConfirmation
     *
     * Isolation (Codex round 2):
     *   - Uses a tool registry containing only `skills_author` — never the full registry
     *   - Calls [ClaudeApiClient.sendMessage] directly, NOT [run] — no AgentEvent emission,
     *     no recursive AgentRuntime entry
     *   - LLM reply is logged but never appended to chat history or sent to channels
     *
     * Boundary (spec §6 ❌): drafted skills are written via `id_strategy=or_suffix` which
     * does NOT auto-enable. User must explicitly enable in the Skills screen.
     */
    internal suspend fun maybeRunSkillFollowup(
        conversationContext: List<ClaudeMessage>,
        usedToolNames: Set<String>,
        successfulToolCount: Int,
        hadRejectedConfirmation: Boolean,
    ) {
        val mode = userPreferences.autoSkillMode.first()
        if (mode == "Off") {
            Log.d(TAG, "autoskill_skipped: reason=mode_off")
            return
        }
        if (successfulToolCount < 2) {
            Log.d(TAG, "autoskill_skipped: reason=too_few_successful_tools count=$successfulToolCount")
            return
        }
        if (usedToolNames.size < 2) {
            Log.d(TAG, "autoskill_skipped: reason=single_tool_workflow used=$usedToolNames")
            return
        }
        if ("skills_author" in usedToolNames) {
            Log.d(TAG, "autoskill_skipped: reason=skills_author_already_used")
            return
        }
        if (hadRejectedConfirmation) {
            Log.d(TAG, "autoskill_skipped: reason=user_rejected_confirmation")
            return
        }

        val providerId = userPreferences.selectedProvider.first()
        val provider = AiProvider.fromId(providerId)
        if (mode == "AutoOnRemote") {
            if (provider.isLocal) {
                Log.d(TAG, "autoskill_skipped: reason=auto_on_remote_but_local")
                return
            }
            if (provider.requiresCustomBaseUrl) {
                val baseUrl = userPreferences.getBaseUrlForProvider(provider.id)
                if ("localhost" in baseUrl || "127.0.0.1" in baseUrl) {
                    Log.d(TAG, "autoskill_skipped: reason=auto_on_remote_but_localhost")
                    return
                }
            }
        }

        val skillsAuthorTool = toolRegistry["skills_author"]
        if (skillsAuthorTool == null) {
            Log.w(TAG, "autoskill_skipped: reason=skills_author_tool_not_registered")
            return
        }

        Log.i(TAG, "autoskill_trigger: tools=${usedToolNames.size} successful=$successfulToolCount")

        val followupSystem = """
            You analyze a completed user task and decide whether the workflow should be saved
            as a reusable skill. Use the skills_author tool with action="create" and id_strategy="or_suffix"
            to save a draft. Drafts will not auto-activate — the user reviews them later.

            Save a skill only if ALL of these are true:
            (a) The conversation shows a generic, repeatable pattern — not a one-off lookup or
                user-specific operation tied to a single account, ID, or piece of data.
            (b) The SKILL.md content contains NO PII, secrets, tokens, account IDs, names, dates,
                or session-specific values. Use placeholders like {{date}} or {{recipient}}.
            (c) The skill spans 2+ tools or steps (single-tool flows aren't worth saving).

            If the criteria are not met, reply with the literal text "no skill" and call no tools.

            SKILL.md format: YAML frontmatter (name, description, tools_required) then body.
        """.trimIndent()

        val recentTurns = conversationContext.takeLast(8).joinToString("\n\n") { msg ->
            val text = when (val c = msg.content) {
                is ClaudeContent.Text -> c.text
                else -> "[non-text content]"
            }
            "[${msg.role}] ${text.take(2000)}"
        }
        val followupUser = "Review this completed task. If a reusable skill emerges, save it as a draft. Otherwise reply 'no skill'.\n\n$recentTurns"

        val isolatedTool = ClaudeTool(
            name = skillsAuthorTool.name,
            description = skillsAuthorTool.description,
            inputSchema = skillsAuthorTool.parameters,
        )

        val request = ClaudeRequest(
            model = userPreferences.selectedModel.first(),
            messages = listOf(ClaudeMessage(role = "user", content = ClaudeContent.Text(followupUser))),
            system = followupSystem,
            tools = listOf(isolatedTool),
            maxTokens = 2048,
        )

        Log.i(TAG, "autoskill_followup_started: provider=${provider.id}")
        val response = try {
            apiClient.sendMessage(request)
        } catch (e: Exception) {
            Log.w(TAG, "autoskill_followup_api_failed: ${e.message?.take(200)}")
            return
        }

        // Execute any skills_author tool_use the LLM emitted. We DO NOT call
        // executeAndEmit because there is no flow collector here — and that's intentional
        // (Codex spec §7E: no chat history pollution, no UI events).
        for (block in response.content) {
            if (block !is ContentBlock.ToolUseBlock) continue
            if (block.name != "skills_author") {
                Log.w(TAG, "autoskill_followup_unexpected_tool: ${block.name} (registry isolation should have prevented this)")
                continue
            }
            val rawInput = block.input.toMap().filterKeys { it != "__confirmed" }
            // Force or_suffix mode regardless of what the LLM put in the call. Boundary
            // §6 ❌: auto-skill follow-up MUST NOT use id_strategy="exact" path because
            // that auto-enables skills.
            val forcedInput = rawInput.toMutableMap().apply {
                this["id_strategy"] = JsonPrimitive("or_suffix")
            }
            val result = try {
                skillsAuthorTool.execute(forcedInput)
            } catch (e: Exception) {
                Log.w(TAG, "autoskill_skill_create_failed: ${e.message?.take(200)}")
                continue
            }
            when (result) {
                is ToolResult.Success -> Log.i(TAG, "autoskill_drafted: ${result.data.take(300)}")
                is ToolResult.Error -> Log.w(TAG, "autoskill_skill_create_error: ${result.message.take(300)}")
                is ToolResult.NeedsConfirmation -> Log.w(TAG, "autoskill_unexpected_confirmation_request: id_strategy=or_suffix should bypass")
            }
        }
    }

    companion object {
        private const val MAX_ITERATIONS = 200
        private const val MAX_TOKENS = 8192
        private const val TAG = "AgentRuntime"
    }
}

/**
 * Format a ClaudeApiException into a user-facing error string, with hints for
 * known provider-specific failure modes (e.g. OpenRouter data-policy guardrails).
 *
 * Design notes on the string-matching gates:
 * - OpenRouter guardrail blocks return HTTP 404 with "guardrail" in the body.
 *   AWS Bedrock Guardrails (a separate product) return 400, not 404, so the
 *   status-code gate is the primary disambiguator; we also exclude bodies
 *   that mention "bedrock" to be safe.
 * - We match OpenRouter's exact phrase "insufficient credits" rather than the
 *   bare word "credit" to avoid misclassifying Stripe/billing errors from
 *   OpenAI/Mistral/Together that mention "credit card", "credit limit", etc.
 */
internal fun formatApiError(e: ClaudeApiException): String {
    val body = e.errorBody
    // OpenRouter: 404 "No endpoints available matching your guardrail restrictions and data policy"
    // is an account-side privacy setting, not a code bug. Surface the fix URL directly.
    if (e.statusCode == 404 &&
        body.contains("guardrail", ignoreCase = true) &&
        !body.contains("bedrock", ignoreCase = true)
    ) {
        return "OpenRouter rejected this model due to your data policy settings. " +
            "Open https://openrouter.ai/settings/privacy on the web, accept the data " +
            "policy for this model family, then retry. (Some free/China-hosted models " +
            "require explicit opt-in.)"
    }
    // OpenRouter: 402 "Insufficient credits" — exact phrase to avoid Stripe false positives
    if (e.statusCode == 402 && body.contains("insufficient credits", ignoreCase = true)) {
        return "Out of credits. Top up your provider account and retry."
    }
    // Bedrock cross-region: 403 AccessDeniedException with "is not authorized to perform: bedrock:InvokeModel"
    if (e.statusCode == 403 && body.contains("bedrock:InvokeModel", ignoreCase = true)) {
        return "Bedrock denied access to this model. Enable model access in the AWS " +
            "Bedrock console (Model access page) for your region, then retry."
    }
    return when (e.statusCode) {
        401 -> "API error (401): Unauthorized. Check your API key in Settings."
        429 -> "API error (429): $body Rate limited. Will retry automatically."
        else -> "API error (${e.statusCode}): $body"
    }
}

/**
 * Format a network-layer exception into a user-facing message that distinguishes
 * DNS failures (UnknownHostException) from connection refusals (ConnectException),
 * with extra hints for self-hosted endpoint scenarios.
 */
internal fun formatNetworkError(e: Throwable): String {
    val host = e.message?.substringBefore(":")?.trim().orEmpty()
    return when (e) {
        is java.net.UnknownHostException ->
            "Could not resolve the server hostname${if (host.isNotEmpty()) " ($host)" else ""}. " +
                "If using Tailscale, verify MagicDNS is enabled in the admin console " +
                "and the phone is connected to the tailnet. For LAN/Ollama, prefer " +
                "the raw IP (e.g. http://192.168.1.50:11434/v1) over a hostname."
        is java.net.ConnectException ->
            "Connection refused. The server isn't reachable. Common causes: " +
                "(1) the local server (Ollama / LM Studio / vLLM) isn't running; " +
                "(2) it's bound to 127.0.0.1 only — set OLLAMA_HOST=0.0.0.0:11434 or " +
                "enable LM Studio's network exposure; (3) the port is blocked by a firewall."
        is java.net.SocketTimeoutException ->
            "The server exceeded the ${HttpTimeouts.REQUEST_MINUTES}-minute response limit. " +
                "For reasoning models (DeepSeek R1, MiniMax M2, Nemotron Ultra, etc.) try " +
                "a simpler prompt or switch to a faster non-reasoning model. Also check " +
                "your network signal (if using Tailscale or another VPN, verify the " +
                "tunnel is up)."
        else ->
            "Network error: ${e.message ?: e.javaClass.simpleName}"
    }
}
