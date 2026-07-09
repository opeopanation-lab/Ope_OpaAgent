package ai.affiora.ope_opaAgent.ui.settings

import ai.affiora.ope_opaAgent.agent.AiProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Item D + E + C1 (v1.2.12) advanced settings page. Three independent feature flags:
 *   - Bedrock Opus 4.7 max thinking (C1, openclaw 9189b16)
 *   - Provider failover chain (D)
 *   - Auto-skill on task complete (E)
 *
 * All default OFF (per spec §11 Rollback). Each can be revert-by-flag without code change.
 */
@Composable
fun AdvancedPage(viewModel: SettingsViewModel) {
    val bedrockMaxThinking by viewModel.bedrockMaxThinkingEnabled.collectAsStateWithLifecycle()
    val failoverEnabled by viewModel.failoverEnabled.collectAsStateWithLifecycle()
    val failoverChain by viewModel.failoverChain.collectAsStateWithLifecycle()
    val failoverMaxAttempts by viewModel.failoverMaxAttempts.collectAsStateWithLifecycle()
    val autoSkillMode by viewModel.autoSkillMode.collectAsStateWithLifecycle()
    val selectedProvider by viewModel.selectedProvider.collectAsStateWithLifecycle()
    val providerTokens by viewModel.providerTokens.collectAsStateWithLifecycle()

    val configuredProviders = providerTokens.filter { it.hasToken && !it.provider.isLocal }.map { it.provider }
    val availableForFailover = configuredProviders.filter { it != selectedProvider }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Provider failover (D) ────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Provider Failover",
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Switch(checked = failoverEnabled, onCheckedChange = viewModel::setFailoverEnabled)
                }
                Text(
                    "Auto-retry on a different provider when the primary returns 429/408/502/503/504 " +
                        "or a network timeout. Auth errors and other 4xx do NOT trigger failover.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (failoverEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Text("Primary: ${selectedProvider.displayName} (current)", style = MaterialTheme.typography.bodyMedium)

                    FallbackPicker(
                        label = "Fallback 1",
                        currentSelection = failoverChain.getOrNull(0),
                        candidates = availableForFailover.filter { it.id != failoverChain.getOrNull(1) },
                        onSelect = { picked ->
                            val newChain = mutableListOf<String>().apply {
                                if (picked != null) add(picked.id)
                                failoverChain.getOrNull(1)?.let { add(it) }
                            }
                            viewModel.setFailoverChain(newChain)
                        },
                    )

                    FallbackPicker(
                        label = "Fallback 2",
                        currentSelection = failoverChain.getOrNull(1),
                        candidates = availableForFailover.filter { it.id != failoverChain.getOrNull(0) },
                        onSelect = { picked ->
                            val newChain = mutableListOf<String>().apply {
                                failoverChain.getOrNull(0)?.let { add(it) }
                                if (picked != null) add(picked.id)
                            }
                            viewModel.setFailoverChain(newChain)
                        },
                    )

                    Text(
                        "Max fallback attempts: $failoverMaxAttempts (1 = try one fallback; 2 = try both)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.setFailoverMaxAttempts(1) }) { Text("1") }
                        OutlinedButton(onClick = { viewModel.setFailoverMaxAttempts(2) }) { Text("2") }
                    }
                }
            }
        }

        // ── Auto-skill (E) ────────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Auto-skill on task complete", fontWeight = FontWeight.SemiBold)
                Text(
                    "When a task uses ≥2 tools successfully, automatically draft a reusable skill " +
                        "from the conversation. Drafts require your approval before they activate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AutoSkillModeRadio("Off", "Off", autoSkillMode, viewModel::setAutoSkillMode)
                AutoSkillModeRadio(
                    "AutoOnRemote",
                    "Auto on remote model (skip local Gemma / localhost CUSTOM)",
                    autoSkillMode,
                    viewModel::setAutoSkillMode,
                )
                AutoSkillModeRadio("Always", "Always (also on local on-device models)", autoSkillMode, viewModel::setAutoSkillMode)
            }
        }

        // ── Bedrock Opus 4.7 max thinking (C1) ─────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Bedrock Opus 4.7 max thinking",
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Switch(checked = bedrockMaxThinking, onCheckedChange = viewModel::setBedrockMaxThinkingEnabled)
                }
                Text(
                    "When enabled and the model is Opus 4.7, request body adds " +
                        "additionalModelRequestFields.output_config.effort=max. " +
                        "No effect on other Bedrock models or other providers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FallbackPicker(
    label: String,
    currentSelection: String?,
    candidates: List<AiProvider>,
    onSelect: (AiProvider?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.weight(1f)) {
                val name = candidates.firstOrNull { it.id == currentSelection }?.displayName
                    ?: currentSelection
                    ?: "(none)"
                Text(name)
            }
            if (currentSelection != null) {
                OutlinedButton(onClick = { onSelect(null) }) { Text("Clear") }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("(none)") },
                onClick = { onSelect(null); expanded = false },
            )
            HorizontalDivider()
            for (provider in candidates) {
                DropdownMenuItem(
                    text = { Text(provider.displayName) },
                    onClick = { onSelect(provider); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun AutoSkillModeRadio(
    value: String,
    label: String,
    currentMode: String,
    onSelect: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        RadioButton(selected = currentMode == value, onClick = { onSelect(value) })
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
