package dev.local.rebuy.autobuy

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.local.rebuy.data.RoutineItem
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AutoBuyStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var file: File
    private lateinit var job: Job
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: AutoBuyStore

    @Before fun setUp() {
        file = File(temporaryFolder.newFolder(), "auto_buy_test.preferences_pb")
        reopen()
    }

    @After fun tearDown() = runBlocking { job.cancelAndJoin() }

    private fun reopen() {
        job = SupervisorJob()
        dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
        store = AutoBuyStore(dataStore)
    }

    @Test fun actualPurchaseCannotBeEnabledClaimedOrSubmitted() = runBlocking {
        val item = item()
        val profile = store.saveProfile(profile(item))
        assertFalse(profile.enabled)
        assertNull(store.claimAttempt(item, profile, dryRun = false))
        assertFalse(store.markPaymentSubmitted("any-attempt"))
        expectInvalid { store.setEnabled(item.id, true) }
        expectInvalid { store.saveProfile(profile.copy(enabled = true)) }
        assertFalse(requireNotNull(store.getProfile(item.id)).enabled)
        assertTrue(store.attempts.first().isEmpty())
    }

    @Test fun profileValidationRequiresExactQuantityTitleOptionAndPositiveLimit() = runBlocking {
        val item = item()
        val valid = profile(item)
        listOf(
            valid.copy(expectedTitle = " "), valid.copy(expectedOption = ""),
            valid.copy(expectedTitle = "a".repeat(151)), valid.copy(maxTotalWon = 0),
            valid.copy(maxTotalWon = 100_000_001), valid.copy(quantitySnapshot = 2),
            valid.copy(productUrlSnapshot = "https://evil.example/product"),
        ).forEach { invalid -> expectInvalid { store.saveProfile(invalid) } }
        val first = store.saveProfile(valid.copy(expectedTitle = " 테스트 상품 "))
        val second = store.saveProfile(first)
        assertEquals("테스트 상품", second.expectedTitle)
        assertNotEquals(first.revisionId, second.revisionId)
        assertEquals(1, store.profiles.first().size)
    }

    @Test fun concurrentInstancesCannotClaimTheSameCycleTwice() = runBlocking {
        val item = item()
        val profile = store.saveProfile(profile(item))
        val claimed = coroutineScope {
            (1..20).map {
                async(Dispatchers.Default) { AutoBuyStore(dataStore).claimAttempt(item, profile, dryRun = true) }
            }.awaitAll().filterNotNull()
        }
        assertEquals(1, claimed.size)
        assertEquals(1, store.attempts.first().size)
        val attempt = claimed.single()
        val mutations = coroutineScope {
            (1..20).map {
                async(Dispatchers.Default) { AutoBuyStore(dataStore).markExternalMutationStarted(attempt.id) }
            }.awaitAll()
        }
        assertEquals(1, mutations.count { it })
        assertFalse(store.markPaymentSubmitted(attempt.id))
        assertNull(requireNotNull(store.getAttempt(attempt.id)).paymentSubmittedAtEpochMillis)
    }

    @Test fun persistedClaimIsNotReplayedAfterAStoreRestart() = runBlocking {
        val item = item()
        val profile = store.saveProfile(profile(item))
        val claimed = requireNotNull(store.claimAttempt(item, profile, dryRun = true))
        assertTrue(file.exists())
        job.cancelAndJoin()
        reopen()
        assertEquals(claimed, store.getAttempt(claimed.id))
        assertEquals(profile, store.getProfile(item.id))
        assertNull(store.claimAttempt(item, profile, dryRun = true))
    }

    @Test fun changedUrlQuantityOrRevisionRejectsExecutionBeforeMutation() = runBlocking {
        val item = item()
        val original = store.saveProfile(profile(item))
        expectInvalid { store.claimAttempt(item.copy(quantity = 2), original, dryRun = true) }
        expectInvalid {
            store.claimAttempt(item.copy(productUrl = item.productUrl + "&different=1"), original, dryRun = true)
        }
        val attempt = requireNotNull(store.claimAttempt(item, original, dryRun = true))
        val changed = store.saveProfile(original.copy(maxTotalWon = 90_000))
        assertNotEquals(original.revisionId, changed.revisionId)
        assertFalse(store.markExternalMutationStarted(attempt.id))
        assertNull(store.claimAttempt(item, original, dryRun = true))
    }

    @Test fun dryRunCanFinishWithoutCreatingAnOrderAndActualClaimsStayBlocked() = runBlocking {
        val item = item()
        val profile = store.saveProfile(profile(item))
        val attempt = requireNotNull(store.claimAttempt(item, profile, dryRun = true))
        assertTrue(store.markExternalMutationStarted(attempt.id))
        store.finishAttempt(attempt.id, AutoBuyOutcome.DryRunReady(75_000))
        val finished = requireNotNull(store.getAttempt(attempt.id))
        assertEquals(AutoBuyStatus.DRY_RUN_READY, finished.status)
        assertEquals(75_000L, finished.totalWon)
        assertNull(finished.orderId)
        assertNull(finished.paymentSubmittedAtEpochMillis)
        assertNull(store.claimAttempt(item, profile, dryRun = false))
        // A later explicit preview may run again, but it still has no payment authorization.
        assertNotNull(store.claimAttempt(item, profile, dryRun = true))
    }

    @Test fun unexpectedConfirmationCannotBecomeConfirmedOrBeReplayed() = runBlocking {
        val item = item()
        val profile = store.saveProfile(profile(item))
        val attempt = requireNotNull(store.claimAttempt(item, profile, dryRun = true))
        store.finishAttempt(attempt.id, AutoBuyOutcome.Confirmed("1000123456789", 75_000))
        val result = requireNotNull(store.getAttempt(attempt.id))
        assertEquals(AutoBuyStatus.UNCERTAIN, result.status)
        assertNull(result.orderId)
        assertNull(store.claimAttempt(item, profile, dryRun = true))
    }

    @Test fun staleWorkerDiagnosticsCannotConsumeOrAuthorizePayment() = runBlocking {
        val item = item()
        val profile = store.saveProfile(profile(item))
        repeat(3) { store.recordBlocked(item, profile, AutoBuyPolicy.PURCHASE_BLOCKED_MESSAGE) }
        val diagnostic = store.attempts.first().single()
        assertEquals(AutoBuyStatus.BLOCKED, diagnostic.status)
        assertFalse(diagnostic.dryRun)
        assertNull(diagnostic.externalMutationAtEpochMillis)
        assertNull(diagnostic.paymentSubmittedAtEpochMillis)
        assertFalse(store.markExternalMutationStarted(diagnostic.id))
        assertFalse(store.markPaymentSubmitted(diagnostic.id))
        assertNotNull(store.claimAttempt(item, profile, dryRun = true))
    }

    @Test fun registeredConditionsRejectAnOldProfileBeforeCreatingAnyAttempt() = runBlocking {
        val item = item().copy(purchaseOption = "600ml, 2개", maxTotalWon = 80_000)
        listOf(
            profile(item).copy(expectedTitle = "이전 상품명"),
            profile(item).copy(expectedOption = "600ml, 1개"),
            profile(item).copy(maxTotalWon = 90_000),
        ).forEach { stale ->
            val saved = store.saveProfile(stale)
            expectInvalid { store.claimAttempt(item, saved, dryRun = true) }
        }
        assertTrue(store.attempts.first().isEmpty())
        val current = store.saveProfile(profile(item))
        assertNotNull(store.claimAttempt(item, current, dryRun = true))
        assertFalse(AutoBuyPolicy.ACTUAL_PURCHASE_ENABLED)
    }

    private suspend fun expectInvalid(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected invalid profile or stale snapshot rejection")
        } catch (_: IllegalArgumentException) {
            // Deliberately fail closed before any external action.
        }
    }

    private fun item() = RoutineItem(
        id = "routine-one", title = "테스트 상품",
        productUrl = "https://www.coupang.com/vp/products/7157272883?itemId=21389601649&vendorItemId=91294601197",
        quantity = 1, intervalDays = 30, nextPurchaseEpochDay = LocalDate.now().toEpochDay(),
        cycleId = "cycle-one",
    )

    private fun profile(item: RoutineItem) = AutoBuyProfile(
        itemId = item.id, expectedTitle = "테스트 상품", expectedOption = "600ml, 2개",
        maxTotalWon = 80_000, productUrlSnapshot = item.productUrl, quantitySnapshot = item.quantity,
    )
}
