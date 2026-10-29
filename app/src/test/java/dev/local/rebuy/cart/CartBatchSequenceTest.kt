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

    @Test fun confirmedAndUncertainReceiptsAreSkippedWithoutCountingAsNewAdditions() = runTest {
        val targets = listOf(target(1), target(2), target(3))
        val initial = listOf(
            CartBatchEntry(targets[0], CartBatchEntryStatus.SKIPPED, "confirmed receipt"),
            CartBatchEntry(targets[1], CartBatchEntryStatus.REVIEW_REQUIRED, "uncertain receipt"),
            CartBatchEntry(targets[2]),
        )
        val started = mutableListOf<String>()
        val published = mutableListOf<CartBatchState>()
        val result = CartBatchSequence.run(targets, {
            started += it.itemId
            CartBatchAttemptResult(true, "confirmed")
        }, { published += it; true }, { false }, initial)
        assertEquals(listOf(targets[2].itemId), started)
        assertEquals(listOf(CartBatchEntryStatus.SKIPPED, CartBatchEntryStatus.REVIEW_REQUIRED, CartBatchEntryStatus.ADDED), result.entries.map { it.status })
        assertEquals(1, result.addedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(1, result.reviewCount)
        assertEquals(initial.take(2), result.entries.take(2))
        assertFalse(result.running)
        assertTrue(published.all { it.entries.take(2) == initial.take(2) })
    }

    @Test fun allRecordedTargetsPublishOnceAndNeverLaunchIncludingCancellationAndWriteFailure() = runTest {
        val targets = listOf(target(1), target(2))
        val initial = targets.map { CartBatchEntry(it, CartBatchEntryStatus.SKIPPED, "old receipt") }
        for (cancelled in listOf(false, true)) {
            for (writeSucceeds in listOf(false, true)) {
                val published = mutableListOf<CartBatchState>()
                val result = CartBatchSequence.run(targets, { fail("No recorded target may launch"); error("unreachable") },
                    { published += it; writeSucceeds }, { cancelled }, initial)
                assertEquals(1, published.size)
                assertFalse(published.single().running)
                assertFalse(result.running)
                assertEquals(initial, result.entries)
                assertEquals(0, result.addedCount)
                if (!writeSucceeds) assertTrue(result.message.contains("저장하지 못했습니다"))
                else if (cancelled) assertTrue(result.message.contains("중지"))
            }
        }
    }

    @Test fun receiptFoundJustBeforeExecutionIsPersistedAndLaterTargetStillRuns() = runTest {
        val targets = listOf(target(1), target(2))
        val started = mutableListOf<String>()
        val published = mutableListOf<CartBatchState>()
        val result = CartBatchSequence.run(targets, {
            started += it.itemId
            if (it == targets.first()) CartBatchAttemptResult(false, "newly found receipt", skipStatus = CartBatchEntryStatus.REVIEW_REQUIRED)
            else CartBatchAttemptResult(true, "confirmed")
        }, { published += it; true }, { false })
        assertEquals(targets.map { it.itemId }, started)
        assertEquals(1, result.addedCount)
        assertEquals(1, result.reviewCount)
        assertTrue(published.any { it.entries.first().status == CartBatchEntryStatus.REVIEW_REQUIRED && it.entries.last().status == CartBatchEntryStatus.PENDING })
    }

    @Test fun onlyExplicitUnavailableFailureContinuesAndIsNeverCountedAsAdded() = runTest {
        val targets = listOf(target(1), target(2))
        val executed = mutableListOf<String>()
        val result = CartBatchSequence.run(targets, {
            executed += it.itemId
            if (it == targets.first()) CartBatchAttemptResult(false, "unavailable", continueAfterFailure = true)
            else CartBatchAttemptResult(true, "confirmed")
        }, { true }, { false })
        assertEquals(targets.map { it.itemId }, executed)
        assertEquals(listOf(CartBatchEntryStatus.FAILED, CartBatchEntryStatus.ADDED), result.entries.map { it.status })
        assertEquals(1, result.failedCount)
        assertEquals(1, result.addedCount)
        assertTrue(result.message.contains("실패 1개"))
        val verified = CartBatchSequence.run(targets, {
            CartBatchAttemptResult(false, "read only", verifiedOnly = true, continueAfterFailure = true)
        }, { true }, { false })
        assertEquals(listOf(CartBatchEntryStatus.FAILED, CartBatchEntryStatus.NOT_RUN), verified.entries.map { it.status })
    }

    @Test fun failedSkipPersistenceNeverStartsFollowingTarget() = runTest {
        val targets = listOf(target(1), target(2))
        val executed = mutableListOf<String>()
        val result = CartBatchSequence.run(targets, {
            executed += it.itemId
            CartBatchAttemptResult(false, "receipt", skipStatus = CartBatchEntryStatus.SKIPPED)
        }, { it.entries.first().status != CartBatchEntryStatus.SKIPPED }, { false })
        assertEquals(listOf(targets.first().itemId), executed)
        assertEquals(listOf(CartBatchEntryStatus.SKIPPED, CartBatchEntryStatus.NOT_RUN), result.entries.map { it.status })
        assertEquals(0, result.addedCount)
    }

    @Test fun mismatchedOrCompletedInitialEntriesAndInvalidSkipStatesNeverPermitFurtherEffects() = runTest {
        val targets = listOf(target(1), target(2))
        for (initial in listOf(
            targets.reversed().map(::CartBatchEntry),
            listOf(CartBatchEntry(target(1))),
            listOf(CartBatchEntry(target(1), CartBatchEntryStatus.ADDED), CartBatchEntry(target(2))),
        )) {
            val failure = runCatching {
                CartBatchSequence.run(targets, { fail("Invalid preflight must not launch"); error("unreachable") }, { true }, { false }, initial)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
        var calls = 0
        val result = CartBatchSequence.run(targets, {
            calls++
            CartBatchAttemptResult(false, "invalid skip", skipStatus = CartBatchEntryStatus.ADDED)
        }, { true }, { false })
        assertEquals(1, calls)
        assertEquals(listOf(CartBatchEntryStatus.FAILED, CartBatchEntryStatus.NOT_RUN), result.entries.map { it.status })
    }

}
