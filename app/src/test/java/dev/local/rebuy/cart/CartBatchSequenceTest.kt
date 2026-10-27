package dev.local.rebuy.cart

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CartBatchSequenceTest {
    private fun target(id: Int) = CartBatchTarget(
        "item-" + id, "확인용 상품 이름 " + id, "https://www.coupang.com/vp/products/" + id + "?itemId=" + id + "1&vendorItemId=" + id + "2", "600ml, " + id + "개",
    )

    @Test fun startsSecondOnlyAfterFirstConfirmedAndPreviousScanUnwinds() = runTest {
        val started = mutableListOf<String>()
        val first = CompletableDeferred<CartBatchAttemptResult>()
        val second = CompletableDeferred<CartBatchAttemptResult>()
        val task = async {
            CartBatchSequence.run(listOf(target(1), target(2)), { item ->
                started += item.itemId
                if (started.size == 1) first.await() else second.await()
            }, { true }, { false })
        }
        runCurrent()
        assertEquals(listOf("item-1"), started)
        first.complete(CartBatchAttemptResult(true, "confirmed"))
        runCurrent()
        assertEquals(listOf("item-1"), started)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("item-1", "item-2"), started)
        second.complete(CartBatchAttemptResult(true, "confirmed"))
        assertEquals(2, task.await().addedCount)
    }

    @Test fun failureOrReadOnlyResultStopsEveryRemainingTargetWithoutRetry() = runTest {
        for (outcome in listOf(CartBatchAttemptResult(false, "not found"), CartBatchAttemptResult(true, "read only", verifiedOnly = true))) {
            val started = mutableListOf<String>()
            val result = CartBatchSequence.run(listOf(target(1), target(2)), {
                started += it.itemId
                outcome
            }, { true }, { false })
            assertEquals(listOf("item-1"), started)
            assertEquals(listOf(CartBatchEntryStatus.FAILED, CartBatchEntryStatus.NOT_RUN), result.entries.map { it.status })
        }
    }

    @Test fun cancellationPreservesCompletedTargetAndNeverStartsNextOne() = runTest {
        var cancelled = false
        val started = mutableListOf<String>()
        val task = async {
            CartBatchSequence.run(listOf(target(1), target(2)), {
                started += it.itemId
                CartBatchAttemptResult(true, "confirmed")
            }, { true }, { cancelled })
        }
        runCurrent()
        cancelled = true
        advanceTimeBy(500)
        val result = task.await()
        assertEquals(listOf("item-1"), started)
        assertEquals(listOf(CartBatchEntryStatus.ADDED, CartBatchEntryStatus.NOT_RUN), result.entries.map { it.status })
    }

    @Test fun failedPersistenceAndDuplicateCanonicalVariantPreventEffects() = runTest {
        var effects = 0
        val execute: suspend (CartBatchTarget) -> CartBatchAttemptResult = { effects++; CartBatchAttemptResult(true, "confirmed") }
        assertFalse(CartBatchSequence.run(listOf(target(1), target(2)), execute, { false }, { false }).running)
        assertEquals(0, effects)
        val error = runCatching {
            CartBatchSequence.run(listOf(target(1), target(1).copy(itemId = "other-id")), execute, { true }, { false })
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, effects)
    }

    @Test fun processRestoreMarksUncertainCurrentTargetAndNeverResumesPending() {
        val previous = CartBatchState(listOf(
            CartBatchEntry(target(1), CartBatchEntryStatus.ADDED),
            CartBatchEntry(target(2), CartBatchEntryStatus.RUNNING),
            CartBatchEntry(target(3)),
        ), running = true)
        val result = CartBatchSequence.interrupted(previous)
        assertFalse(result.running)
        assertTrue(result.interrupted)
        assertEquals(listOf(CartBatchEntryStatus.ADDED, CartBatchEntryStatus.FAILED, CartBatchEntryStatus.NOT_RUN), result.entries.map { it.status })
    }
}
