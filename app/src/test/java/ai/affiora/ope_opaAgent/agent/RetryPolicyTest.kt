package ai.affiora.ope_opaAgent.agent

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Item D (v1.2.12) — pure data tests of [RetryPolicy].
 * Codex round 3 reaffirmed: retry list is 429/408/502/503/504 + SocketTimeout/ConnectException.
 * 4xx logic errors and 500 must NOT trigger failover.
 */
class RetryPolicyTest {

    private val policy = RetryPolicy(maxFailoverAttempts = 2)

    @Test
    fun shouldFailover_on429_returnsTrue() {
        assertThat(policy.shouldFailover(429, null, 0)).isTrue()
    }

    @Test
    fun shouldFailover_on408_returnsTrue() {
        assertThat(policy.shouldFailover(408, null, 0)).isTrue()
    }

    @Test
    fun shouldFailover_on502_503_504_returnsTrue() {
        assertThat(policy.shouldFailover(502, null, 0)).isTrue()
        assertThat(policy.shouldFailover(503, null, 0)).isTrue()
        assertThat(policy.shouldFailover(504, null, 0)).isTrue()
    }

    @Test
    fun shouldFailover_on500_returnsFalse() {
        // 500 is ambiguous (server bug vs transient). Failing over creates loops on
        // non-transient bugs.
        assertThat(policy.shouldFailover(500, null, 0)).isFalse()
    }

    @Test
    fun shouldFailover_on501_returnsFalse() {
        assertThat(policy.shouldFailover(501, null, 0)).isFalse()
    }

    @Test
    fun shouldFailover_on401_403_returnsFalse() {
        // Auth errors — no point trying fallback with same key context. Spec §6 ❌.
        assertThat(policy.shouldFailover(401, null, 0)).isFalse()
        assertThat(policy.shouldFailover(403, null, 0)).isFalse()
    }

    @Test
    fun shouldFailover_on400_413_returnsFalse() {
        // Invalid request / context too long — fallback won't help.
        assertThat(policy.shouldFailover(400, null, 0)).isFalse()
        assertThat(policy.shouldFailover(413, null, 0)).isFalse()
    }

    @Test
    fun shouldFailover_onSocketTimeout_returnsTrue() {
        assertThat(policy.shouldFailover(null, SocketTimeoutException("timeout"), 0)).isTrue()
    }

    @Test
    fun shouldFailover_onConnectException_returnsTrue() {
        assertThat(policy.shouldFailover(null, ConnectException("refused"), 0)).isTrue()
    }

    @Test
    fun shouldFailover_walksCausalChain_forNetworkException() {
        // Ktor wraps low-level exceptions; we must walk cause chain.
        val wrapped = RuntimeException("ktor wrapper", SocketTimeoutException("inner timeout"))
        assertThat(policy.shouldFailover(null, wrapped, 0)).isTrue()
    }

    @Test
    fun shouldFailover_onGenericIOException_returnsFalse() {
        // IOException without specific cause — don't generalize.
        assertThat(policy.shouldFailover(null, IOException("disk full"), 0)).isFalse()
    }

    @Test
    fun shouldFailover_respectsMaxAttemptsBound() {
        val tightPolicy = RetryPolicy(maxFailoverAttempts = 1)
        assertThat(tightPolicy.shouldFailover(429, null, 0)).isTrue() // first failover OK
        assertThat(tightPolicy.shouldFailover(429, null, 1)).isFalse() // second exceeds bound
    }

    @Test
    fun shouldFailover_zeroMaxAttempts_neverRetries() {
        val zero = RetryPolicy(maxFailoverAttempts = 0)
        assertThat(zero.shouldFailover(429, null, 0)).isFalse()
    }

    @Test
    fun shouldFailover_nullCodeAndNullThrowable_returnsFalse() {
        assertThat(policy.shouldFailover(null, null, 0)).isFalse()
    }

    /**
     * Codex round 5 regression — when failing over to a different provider, the model ID
     * must be swapped because providers expose disjoint model catalogs. This test only
     * exercises RetryPolicy directly (model swap happens in ClaudeApiClient.sendMessage),
     * but documents the contract: a "shouldFailover=true" decision implies caller must
     * also adapt the request body for the next provider.
     */
    @Test
    fun shouldFailover_decisionIs_independent_ofModelSwap() {
        // RetryPolicy decides WHETHER to fail over. Caller (ClaudeApiClient) decides
        // HOW to adapt the request (model swap). Keep these concerns separated.
        assertThat(policy.shouldFailover(429, null, 0)).isTrue()
        // No model-related state in RetryPolicy — by design.
    }
}
