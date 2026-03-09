package io.github.dalapenko.ktor.websocket.reconnect

import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {

    @Test
    fun `init validation - maxRetries below -1 throws`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            RetryPolicy(maxRetries = -2)
        }
        assertTrue(exception.message!!.contains("maxRetries must be >= -1"))
    }

    @Test
    fun `init validation - negative initialDelay throws`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            RetryPolicy(initialDelay = (-1).seconds)
        }
        assertTrue(exception.message!!.contains("initialDelay must be positive"))
    }

    @Test
    fun `init validation - maxDelay less than initialDelay throws`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            RetryPolicy(
                initialDelay = 10.seconds,
                maxDelay = 5.seconds
            )
        }
        assertTrue(exception.message!!.contains("maxDelay must be >= initialDelay"))
    }

    @Test
    fun `init validation - delayMultiplier below 1_0 throws`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            RetryPolicy(delayMultiplier = 0.5)
        }
        assertTrue(exception.message!!.contains("delayMultiplier must be >= 1.0"))
    }

    @Test
    fun `init validation - jitterFactor out of range throws`() {
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy(jitterFactor = -0.1)
        }
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy(jitterFactor = 1.5)
        }
    }

    @Test
    fun `shouldRetry with NO_RETRY returns false`() {
        val policy = RetryPolicy.NO_RETRY

        assertFalse(policy.shouldRetry(0))
        assertFalse(policy.shouldRetry(1))
        assertFalse(policy.shouldRetry(100))
        assertTrue(policy.isNoRetry)
        assertFalse(policy.isInfinite)
    }

    @Test
    fun `shouldRetry with INFINITE always returns true`() {
        val policy = RetryPolicy.INFINITE

        assertTrue(policy.shouldRetry(0))
        assertTrue(policy.shouldRetry(1))
        assertTrue(policy.shouldRetry(100))
        assertTrue(policy.shouldRetry(10000))
        assertTrue(policy.isInfinite)
        assertFalse(policy.isNoRetry)
    }

    @Test
    fun `shouldRetry respects maxRetries limit`() {
        val policy = RetryPolicy(maxRetries = 3)

        assertTrue(policy.shouldRetry(0))
        assertTrue(policy.shouldRetry(1))
        assertTrue(policy.shouldRetry(2))
        assertFalse(policy.shouldRetry(3))
        assertFalse(policy.shouldRetry(4))
    }

    @Test
    fun `shouldRetry with exception filter allows retry`() {
        val policy = RetryPolicy(
            maxRetries = 5,
            retryOnException = { it is IllegalStateException }
        )

        assertTrue(policy.shouldRetry(0, IllegalStateException("test")))
        assertTrue(policy.shouldRetry(1, IllegalStateException("test")))
    }

    @Test
    fun `shouldRetry with exception filter blocks retry`() {
        val policy = RetryPolicy(
            maxRetries = 5,
            retryOnException = { it is IllegalStateException }
        )

        assertFalse(policy.shouldRetry(0, IllegalArgumentException("test")))
        assertFalse(policy.shouldRetry(1, RuntimeException("test")))
    }

    @Test
    fun `calculateDelay returns initialDelay on first attempt`() {
        val policy = RetryPolicy(
            initialDelay = 2.seconds,
            jitterFactor = 0.0  // No jitter for predictable test
        )

        val delay = policy.calculateDelay(0)
        assertEquals(2000, delay.inWholeMilliseconds)
    }

    @Test
    fun `calculateDelay applies exponential backoff`() {
        val policy = RetryPolicy(
            initialDelay = 1.seconds,
            maxDelay = 60.seconds,
            delayMultiplier = 2.0,
            jitterFactor = 0.0  // No jitter for predictable test
        )

        val delay0 = policy.calculateDelay(0)
        val delay1 = policy.calculateDelay(1)
        val delay2 = policy.calculateDelay(2)
        val delay3 = policy.calculateDelay(3)

        assertEquals(1000, delay0.inWholeMilliseconds)
        assertEquals(2000, delay1.inWholeMilliseconds)
        assertEquals(4000, delay2.inWholeMilliseconds)
        assertEquals(8000, delay3.inWholeMilliseconds)
    }

    @Test
    fun `calculateDelay caps at maxDelay`() {
        val policy = RetryPolicy(
            initialDelay = 1.seconds,
            maxDelay = 5.seconds,
            delayMultiplier = 2.0,
            jitterFactor = 0.0  // No jitter for predictable test
        )

        val delay0 = policy.calculateDelay(0)
        val delay5 = policy.calculateDelay(5)
        val delay10 = policy.calculateDelay(10)

        assertEquals(1000, delay0.inWholeMilliseconds)
        assertEquals(5000, delay5.inWholeMilliseconds)  // 32s capped to 5s
        assertEquals(5000, delay10.inWholeMilliseconds) // 1024s capped to 5s
    }

    @Test
    fun `calculateDelay applies jitter within bounds`() {
        val policy = RetryPolicy(
            initialDelay = 1.seconds,
            maxDelay = 60.seconds,
            delayMultiplier = 2.0,
            jitterFactor = 0.1  // 10% jitter
        )

        // Run multiple times to check jitter variance
        val delays = (0..20).map { policy.calculateDelay(0).inWholeMilliseconds }

        // All delays should be around 1000ms ± 10%
        delays.forEach { delay ->
            assertTrue(delay >= 900, "Delay $delay should be >= 900ms")
            assertTrue(delay <= 1100, "Delay $delay should be <= 1100ms")
        }

        // There should be some variance (not all the same)
        val uniqueDelays = delays.toSet()
        assertTrue(uniqueDelays.size > 1, "Expected variance in delays with jitter")
    }

    @Test
    fun `calculateDelay with zero jitter has no variation`() {
        val policy = RetryPolicy(
            initialDelay = 2.seconds,
            jitterFactor = 0.0
        )

        val delays = (0..10).map { policy.calculateDelay(0).inWholeMilliseconds }

        // All delays should be exactly the same
        assertEquals(1, delays.toSet().size)
        assertEquals(2000, delays.first())
    }

    @Test
    fun `calculateDelay minimum is 100ms`() {
        val policy = RetryPolicy(
            initialDelay = 100.milliseconds,
            delayMultiplier = 1.0,
            jitterFactor = 0.5  // Large jitter could theoretically go negative
        )

        // Even with large jitter, should never go below 100ms
        repeat(50) {
            val delay = policy.calculateDelay(0)
            assertTrue(delay.inWholeMilliseconds >= 100, "Delay should be at least 100ms")
        }
    }

    @Test
    fun `toString formats correctly for DEFAULT policy`() {
        val policy = RetryPolicy.DEFAULT
        val str = policy.toString()

        assertTrue(str.contains("maxRetries=5"))
        assertTrue(str.contains("initialDelay=2s"))
        assertTrue(str.contains("maxDelay=30s"))
        assertTrue(str.contains("multiplier=2.0"))
    }

    @Test
    fun `toString formats correctly for INFINITE policy`() {
        val policy = RetryPolicy.INFINITE
        val str = policy.toString()

        assertTrue(str.contains("maxRetries=infinite"))
    }

    @Test
    fun `toString formats correctly for NO_RETRY policy`() {
        val policy = RetryPolicy.NO_RETRY
        val str = policy.toString()

        assertTrue(str.contains("maxRetries=disabled"))
    }

    @Test
    fun `AGGRESSIVE policy has shorter delays`() {
        val aggressive = RetryPolicy.AGGRESSIVE

        assertEquals(10, aggressive.maxRetries)
        assertEquals(500, aggressive.initialDelay.inWholeMilliseconds)
        assertEquals(10000, aggressive.maxDelay.inWholeMilliseconds)
        assertEquals(1.5, aggressive.delayMultiplier)
    }

    @Test
    fun `CONSERVATIVE policy has longer delays and fewer retries`() {
        val conservative = RetryPolicy.CONSERVATIVE

        assertEquals(3, conservative.maxRetries)
        assertEquals(5000, conservative.initialDelay.inWholeMilliseconds)
        assertEquals(60000, conservative.maxDelay.inWholeMilliseconds)
        assertEquals(3.0, conservative.delayMultiplier)
    }
}
