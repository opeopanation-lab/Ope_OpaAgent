package ai.affiora.ope_opaAgent.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferences(private val context: Context) {

    private object Keys {
        val SELECTED_PROVIDER = stringPreferencesKey("selected_provider")
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val ACTIVE_SKILL_IDS = stringSetPreferencesKey("active_skill_ids")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val PERMISSION_MODE = stringPreferencesKey("permission_mode")
        val ALLOWED_TOOLS = stringSetPreferencesKey("allowed_tools")
        // C1 (openclaw 9189b16): when true and model is opus-4-7, request body adds
        // additionalModelRequestFields.output_config.effort=max for max thinking.
        val BEDROCK_MAX_THINKING_ENABLED = booleanPreferencesKey("bedrock_max_thinking_enabled")
        // D feature flag — when false, ClaudeApiClient.sendMessage runs single attempt with no fallback.
        val FAILOVER_ENABLED = booleanPreferencesKey("failover_enabled")
        val FAILOVER_CHAIN = stringSetPreferencesKey("failover_chain")
        val FAILOVER_CHAIN_ORDER = stringPreferencesKey("failover_chain_order")
        val FAILOVER_MAX_ATTEMPTS = stringPreferencesKey("failover_max_attempts")
        // E feature flag — Off | AutoOnRemote | Always
        val AUTO_SKILL_MODE = stringPreferencesKey("auto_skill_mode")
        // Per-provider base URL override (used by CUSTOM provider for Ollama/LM Studio/vLLM)
        fun baseUrlKey(providerId: String) = stringPreferencesKey("base_url_$providerId")
        // Per-provider key prefix in EncryptedSharedPreferences
        fun tokenKey(providerId: String) = "token_$providerId"
    }

    /** Encrypted storage for all API tokens — backed by Android Keystore. */
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "androidclaw_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            android.util.Log.e("UserPreferences", "Keystore corrupted, resetting", e)
            context.deleteSharedPreferences("androidclaw_secure_prefs")
            // Retry after clearing corrupted prefs
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "androidclaw_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    // Track tokens reactively per provider
    private val _tokenUpdates = MutableStateFlow(0L)

    /** Get the API token for the currently selected provider. */
    val apiKey: Flow<String> = combine(
        context.dataStore.data.map { it[Keys.SELECTED_PROVIDER] ?: DEFAULT_PROVIDER },
        _tokenUpdates,
    ) { providerId, _ ->
        getTokenForProvider(providerId)
    }

    val selectedProvider: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_PROVIDER] ?: DEFAULT_PROVIDER
    }

    val selectedModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_MODEL] ?: DEFAULT_MODEL
    }

    val activeSkillIds: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_SKILL_IDS] ?: emptySet()
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_COMPLETED] ?: false
    }

    val deviceName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEVICE_NAME] ?: ""
    }

    val permissionMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.PERMISSION_MODE] ?: "default"
    }

    val allowedTools: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.ALLOWED_TOOLS] ?: emptySet()
    }

    val bedrockMaxThinkingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BEDROCK_MAX_THINKING_ENABLED] ?: false
    }

    val failoverEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.FAILOVER_ENABLED] ?: false
    }

    val failoverChain: Flow<List<String>> = context.dataStore.data.map { prefs ->
        // Stored as ordered comma-separated string to preserve order; SetPreferenceKey
        // is unordered. Empty list when disabled or not yet configured.
        prefs[Keys.FAILOVER_CHAIN_ORDER]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    val failoverMaxAttempts: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[Keys.FAILOVER_MAX_ATTEMPTS]?.toIntOrNull() ?: 1).coerceIn(1, 2)
    }

    val autoSkillMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUTO_SKILL_MODE] ?: "Off"
    }

    /** Read token for a specific provider. */
    fun getTokenForProvider(providerId: String): String {
        return encryptedPrefs.getString(Keys.tokenKey(providerId), "") ?: ""
    }

    /** Check if a provider has a token configured. */
    fun hasTokenForProvider(providerId: String): Boolean {
        return getTokenForProvider(providerId).isNotBlank()
    }

    /** Set token for a specific provider. */
    suspend fun setTokenForProvider(providerId: String, token: String) {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString(Keys.tokenKey(providerId), token).apply()
        }
        _tokenUpdates.value = System.currentTimeMillis()
    }

    /** Set token for the currently selected provider. */
    suspend fun setApiKey(apiKey: String) {
        val providerId = selectedProvider.first()
        setTokenForProvider(providerId, apiKey)
    }

    /** Read the per-provider base URL override. Empty if not configured. */
    suspend fun getBaseUrlForProvider(providerId: String): String {
        return context.dataStore.data.map { it[Keys.baseUrlKey(providerId)] ?: "" }.first()
    }

    /** Reactive flow of base URL for a specific provider. */
    fun baseUrlFlowForProvider(providerId: String): Flow<String> =
        context.dataStore.data.map { it[Keys.baseUrlKey(providerId)] ?: "" }

    /** Set the per-provider base URL override. */
    suspend fun setBaseUrlForProvider(providerId: String, baseUrl: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.baseUrlKey(providerId)] = baseUrl
        }
    }

    suspend fun setSelectedProvider(provider: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SELECTED_PROVIDER] = provider
        }
    }

    suspend fun setSelectedModel(model: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SELECTED_MODEL] = model
        }
    }

    suspend fun setActiveSkillIds(skillIds: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_SKILL_IDS] = skillIds
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETED] = completed
        }
        val flagFile = context.filesDir.resolve(".onboarding_completed")
        if (completed) {
            flagFile.createNewFile()
        } else {
            flagFile.delete()
        }
    }

    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEVICE_NAME] = name
        }
    }

    suspend fun setPermissionMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PERMISSION_MODE] = mode
        }
    }

    suspend fun setAllowedTools(tools: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ALLOWED_TOOLS] = tools
        }
    }

    suspend fun setBedrockMaxThinkingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BEDROCK_MAX_THINKING_ENABLED] = enabled
        }
    }

    suspend fun setFailoverEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FAILOVER_ENABLED] = enabled
        }
    }

    suspend fun setFailoverChain(providerIds: List<String>) {
        // Migration §12: drop empties / dupes / blanks; preserve order via comma-joined string.
        val cleaned = providerIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        context.dataStore.edit { prefs ->
            prefs[Keys.FAILOVER_CHAIN_ORDER] = cleaned.joinToString(",")
        }
    }

    suspend fun setFailoverMaxAttempts(n: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FAILOVER_MAX_ATTEMPTS] = n.coerceIn(1, 2).toString()
        }
    }

    suspend fun setAutoSkillMode(mode: String) {
        // Allowed values: Off | AutoOnRemote | Always
        val valid = if (mode in setOf("Off", "AutoOnRemote", "Always")) mode else "Off"
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_SKILL_MODE] = valid
        }
    }

    suspend fun addAllowedTool(toolName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.ALLOWED_TOOLS] ?: emptySet()
            prefs[Keys.ALLOWED_TOOLS] = current + toolName
        }
    }

    suspend fun removeAllowedTool(toolName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.ALLOWED_TOOLS] ?: emptySet()
            prefs[Keys.ALLOWED_TOOLS] = current - toolName
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().clear().apply()
        }
    }

    companion object {
        const val DEFAULT_PROVIDER = "anthropic"
        const val DEFAULT_MODEL = "claude-sonnet-4-6"
    }
}
