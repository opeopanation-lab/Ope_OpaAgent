package ai.affiora.ope_opaAgent.agent

import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Pure-data retry policy used by [ClaudeApiClient] for cross-provider failover.
 *
 * Decoupled from network code so it can be unit-tested without HTTP mocks. Boundary
 * (spec §7D + Codex round 3 Q1): the policy says "should retry on a different provider"
 * — it does NOT issue retries within the same provider. Intra-provider backoff for
 * 429/503/529 lives in [ClaudeApiClient.withRetry] and runs first; this policy decides
 * whether to give up on the primary and try the next failover entry.
 *
 * Codex round 3 reaffirmed the retry list:
 *   - **Retry-on-failover**: 429, 408, 502, 503, 504, SocketTimeoutException, ConnectException
 *   - **Do NOT failover**: any other 4xx (400/401/403/413), 500 (ambiguous, prefer no retry
 *     to avoid loops), 501, plus non-network exceptions
 */
data class RetryPolicy(
    val maxFailoverAttempts: Int = 1,
) {
    /**
     * @param httpCode HTTP status from a failed request, or null if exception path
     * @param throwable the exception that caused failure, or null if HTTP-only failure
     * @param attemptIndex 0-based: 0 means primary just failed, considering 1st failover
     * @return true if the caller should try the next provider in the failover chain
     */
    fun shouldFailover(httpCode: Int?, throwable: Throwable?, attemptIndex: Int): Boolean {
        if (attemptIndex >= maxFailoverAttempts) return false

        if (httpCode != null) return httpCode in RETRYABLE_HTTP_CODES

        if (throwable != null) {
            // Walk causal chain — Ktor wraps low-level exceptions
            var t: Throwable? = throwable
            while (t != null) {
                if (t is SocketTimeoutException || t is ConnectException) return true
                t = t.cause
            }
        }
        return false
    }

    companion object {
        /**
         * HTTP codes that warrant trying a different provider. 500 is intentionally NOT
         * here — it's ambiguous (server bug vs transient) and retrying creates loops with
         * non-transient bugs. 429/408/502/503/504 are all explicitly transient.
         *
         * Boundary (spec §6 ❌ Codex never #1): NEVER add 4xx logic errors (400/401/403/413)
         * — failing over on bad-key would burn user's fallback quota with the same error.
         */
        val RETRYABLE_HTTP_CODES: Set<Int> = setOf(429, 408, 502, 503, 504)
    }
}
