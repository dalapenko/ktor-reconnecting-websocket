package io.github.dalapenko.ktor.websocket.reconnect

import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for WebSocket reconnection retry behavior.
 * 
 * Supports exponential backoff with jitter to prevent thundering herd problems.
 * 
 * ## Retry Semantics:
 * - `maxRetries = -1` → Infinite retries (never give up)
 * - `maxRetries = 0` → No reconnection (fail immediately on disconnect)
 * - `maxRetries = N` (positive) → Retry up to N times, then stop gracefully
 * 
 * ## Example Usage:
 * ```kotlin
 * // Default policy: 5 retries with exponential backoff
 * val default = RetryPolicy.DEFAULT
 * 
 * // Infinite retries for critical connections
 * val infinite = RetryPolicy.INFINITE
 * 
 * // No retry - fail immediately
 * val noRetry = RetryPolicy.NO_RETRY
 * 
 * // Custom policy
 * val custom = RetryPolicy(
 *     maxRetries = 10,
 *     initialDelay = 1.seconds,
 *     maxDelay = 60.seconds,
 *     delayMultiplier = 2.0
 * )
 * ```
 * 
 * @param maxRetries Maximum number of retry attempts. -1 for infinite, 0 for no retry.
 * @param initialDelay Delay before the first retry attempt.
 * @param maxDelay Maximum delay between retry attempts (caps exponential growth).
 * @param delayMultiplier Multiplier for exponential backoff (e.g., 2.0 doubles delay each time).
 * @param jitterFactor Random jitter factor (0.0 to 1.0) to prevent synchronized retries.
 * @param retryOnException Predicate to determine if a specific exception should trigger retry.
 */
data class RetryPolicy(
    val maxRetries: Int = 5,
    val initialDelay: Duration = 2.seconds,
    val maxDelay: Duration = 30.seconds,
    val delayMultiplier: Double = 2.0,
    val jitterFactor: Double = 0.1,
    val retryOnException: (Throwable) -> Boolean = { true }
) {
    init {
        require(maxRetries >= -1) { "maxRetries must be >= -1 (use -1 for infinite)" }
        require(initialDelay.isPositive()) { "initialDelay must be positive" }
        require(maxDelay.isPositive()) { "maxDelay must be positive" }
        require(maxDelay >= initialDelay) { "maxDelay must be >= initialDelay" }
        require(delayMultiplier >= 1.0) { "delayMultiplier must be >= 1.0" }
        require(jitterFactor in 0.0..1.0) { "jitterFactor must be between 0.0 and 1.0" }
    }

    companion object {
        /**
         * No retry - connection fails immediately on error.
         */
        val NO_RETRY = RetryPolicy(maxRetries = 0)

        /**
         * Infinite retries - never give up reconnecting.
         * Uses default delays with exponential backoff.
         */
        val INFINITE = RetryPolicy(maxRetries = -1)

        /**
         * Default policy - 5 retries with 2s initial delay, 30s max delay.
         */
        val DEFAULT = RetryPolicy()

        /**
         * Aggressive retry - 10 retries with shorter delays.
         * Useful for connections expected to recover quickly.
         */
        val AGGRESSIVE = RetryPolicy(
            maxRetries = 10,
            initialDelay = 500.milliseconds,
            maxDelay = 10.seconds,
            delayMultiplier = 1.5
        )

        /**
         * Conservative retry - fewer retries with longer delays.
         * Useful for connections to potentially overloaded servers.
         */
        val CONSERVATIVE = RetryPolicy(
            maxRetries = 3,
            initialDelay = 5.seconds,
            maxDelay = 60.seconds,
            delayMultiplier = 3.0
        )
    }

    /**
     * Returns true if this policy is configured for infinite retries.
     */
    val isInfinite: Boolean get() = maxRetries == -1

    /**
     * Returns true if this policy is configured to not retry at all.
     */
    val isNoRetry: Boolean get() = maxRetries == 0

    /**
     * Determines if a retry should be attempted based on the current attempt number.
     * 
     * @param attempt The current attempt number (0-based)
     * @return true if another retry should be attempted
     */
    fun shouldRetry(attempt: Int): Boolean = when {
        isNoRetry -> false
        isInfinite -> true
        else -> attempt < maxRetries
    }

    /**
     * Determines if a retry should be attempted for a specific exception.
     * 
     * @param attempt The current attempt number (0-based)
     * @param cause The exception that caused the failure
     * @return true if another retry should be attempted
     */
    fun shouldRetry(attempt: Int, cause: Throwable): Boolean {
        return shouldRetry(attempt) && retryOnException(cause)
    }

    /**
     * Calculates the delay before the next retry attempt using exponential backoff with jitter.
     * 
     * Formula: min(initialDelay * multiplier^attempt, maxDelay) ± jitter
     * 
     * @param attempt The current attempt number (0-based)
     * @return Duration to wait before the next retry
     */
    fun calculateDelay(attempt: Int): Duration {
        // Calculate exponential delay
        val exponentialDelayMs = initialDelay.inWholeMilliseconds * delayMultiplier.pow(attempt)

        // Cap at maximum delay
        val cappedDelayMs = minOf(exponentialDelayMs.toLong(), maxDelay.inWholeMilliseconds)

        // Apply jitter (random variation to prevent thundering herd)
        val jitterMs = if (jitterFactor > 0) {
            (cappedDelayMs * Random.nextDouble(-jitterFactor, jitterFactor)).toLong()
        } else {
            0L
        }

        // Ensure delay is at least 100ms
        val finalDelayMs = maxOf(100L, cappedDelayMs + jitterMs)

        return finalDelayMs.milliseconds
    }

    /**
     * Returns a human-readable description of this retry policy.
     */
    override fun toString(): String {
        val retriesStr = when {
            isInfinite -> "infinite"
            isNoRetry -> "disabled"
            else -> maxRetries.toString()
        }
        return "RetryPolicy(maxRetries=$retriesStr, initialDelay=$initialDelay, maxDelay=$maxDelay, multiplier=$delayMultiplier)"
    }
}
