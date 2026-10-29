package dev.local.rebuy.cart

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CartAdditionLedgerGenericIdentityTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private val basic = "https://www.coupang.com/vp/products/123456"
    private val exact = "$basic?itemId=333&vendorItemId=444"
    private val otherOption = "$basic?itemId=334&vendorItemId=445"
    private val otherProduct = "https://www.coupang.com/vp/products/765432?itemId=555&vendorItemId=666"
    private val urls get() = listOf(basic, exact, otherOption, otherProduct)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = context.getSharedPreferences("cart_addition_ledger_v1", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        urls.forEach { assertTrue(CartAdditionLedger(context).clear(it)) }
    }

    @After fun cleanUp() {
        urls.forEach { assertTrue(CartAdditionLedger(context).clear(it)) }
        assertTrue(preferences.edit().clear().commit())
    }

    @Test fun basicClaimBlocksTheProductAcrossExactOptionsAndUrlSpelling() {
        val ledger = CartAdditionLedger(context)
        assertTrue(ledger.claim(basic, "basic-first"))
        val record = requireNotNull(ledger.lookup(basic))
        val reopened = CartAdditionLedger(context)
        assertEquals(record, reopened.lookup("https://m.coupang.com/vp/products/123456?sourceType=share"))
        assertEquals(record, reopened.lookup(exact))
        assertEquals(record, reopened.lookup(otherOption))
        assertFalse(reopened.claim(exact, "exact-later"))
        assertFalse(reopened.claim(otherOption, "other-option-later"))
        assertTrue(reopened.claim(otherProduct, "other-product"))
    }

    @Test fun exactOptionsRemainIndependentButEitherOneBlocksBasicAddition() {
        val ledger = CartAdditionLedger(context)
        assertTrue(ledger.claim(exact, "exact-first"))
        assertNull(ledger.lookup(otherOption))
        assertTrue(ledger.claim(otherOption, "other-option"))
        assertNotNull(CartAdditionLedger(context).lookup(basic))
        assertFalse(CartAdditionLedger(context).claim(basic, "basic-later"))
        assertEquals(2, preferences.all.size)
    }

    @Test fun basicConfirmationNeedsItsOwnAttemptAndSurvivesReopening() {
        val ledger = CartAdditionLedger(context)
        assertTrue(ledger.claim(basic, "owner"))
        assertFalse(ledger.confirm(basic, "another"))
        assertFalse(ledger.confirm(exact, "owner"))
        assertTrue(ledger.confirm(basic, "owner"))
        assertFalse(ledger.confirm(basic, "owner"))
        assertEquals(CartAdditionLedger.Status.CONFIRMED, CartAdditionLedger(context).lookup(exact)?.status)
    }

    @Test fun clearingBasicNeverErasesExactReceiptsAndClearingExactNeverErasesBasicReceipt() {
        val ledger = CartAdditionLedger(context)
        assertTrue(ledger.claim(exact, "exact-owner"))
        assertTrue(ledger.clear(basic))
        assertNotNull(ledger.lookup(exact))
        assertFalse(ledger.claim(basic, "still-blocked"))
        assertTrue(ledger.clear(exact))
        assertTrue(ledger.claim(basic, "basic-owner"))
        assertTrue(ledger.clear(exact))
        assertNotNull(ledger.lookup(basic))
        assertFalse(ledger.claim(exact, "still-blocked-exact"))
    }

    @Test fun unknownLegacyReceiptBlocksBasicButDoesNotGloballyBlockExactOptions() {
        val ledger = CartAdditionLedger(context)
        assertTrue(ledger.claim(exact, "old-owner"))
        downgradeToLegacy(exact)
        expectInvalid { CartAdditionLedger(context).lookup(basic) }
        expectInvalid { CartAdditionLedger(context).claim(basic, "unsafe-basic") }
        assertEquals(CartAdditionLedger.Status.CLAIMED, CartAdditionLedger(context).lookup(exact)?.status)
        assertTrue(CartAdditionLedger(context).claim(otherProduct, "unrelated-exact"))
        assertTrue(CartAdditionLedger(context).claim(otherOption, "different-exact-option"))
        assertEquals(1, JSONObject(preferences.getString(digest(exact), null)!!).getInt("version"))
    }

    @Test fun migrationPersistsOnlyHashMatchedKnownUrlsWithoutChangingReceiptMetadataOrKeys() {
        val ledger = CartAdditionLedger(context)
        assertTrue(ledger.claim(exact, "old-owner"))
        assertTrue(ledger.confirm(exact, "old-owner"))
        val oldRecord = requireNotNull(ledger.lookup(exact))
        val legacy = downgradeToLegacy(exact)
        val oldKey = preferences.all.keys.single()
        val equivalent = "https://m.coupang.com/vp/products/123456?sourceType=share&vendorItemId=444&itemId=333"
        assertTrue(ledger.migrateKnownUrls(listOf(basic, "not a URL", equivalent)))
        assertEquals(setOf(oldKey), preferences.all.keys)
        val migrated = JSONObject(preferences.getString(oldKey, null)!!)
        assertEquals(2, migrated.getInt("version"))
        assertEquals(exact, migrated.getString("canonicalUrl"))
        for (field in listOf("status", "attemptId", "atEpochMillis")) assertEquals(legacy.get(field), migrated.get(field))
        val reopened = CartAdditionLedger(context)
        assertEquals(oldRecord, reopened.lookup(basic))
        assertFalse(reopened.claim(basic, "duplicate"))
        assertEquals(oldRecord, reopened.lookup(equivalent))
    }

    @Test fun partialMigrationLeavesUnknownLegacyReceiptIntactAndBasicStillFailsClosed() {
        val ledger = CartAdditionLedger(context)
        assertTrue(ledger.claim(exact, "known"))
        assertTrue(ledger.claim(otherProduct, "unknown"))
        downgradeToLegacy(exact)
        val unknown = downgradeToLegacy(otherProduct).toString()
        assertTrue(ledger.migrateKnownUrls(listOf(exact)))
        assertEquals(unknown, preferences.getString(digest(otherProduct), null))
        expectInvalid { CartAdditionLedger(context).lookup(basic) }
        assertFalse(CartAdditionLedger(context).claim(exact, "exact-duplicate"))
        assertTrue(ledger.migrateKnownUrls(listOf(otherProduct)))
        assertNotNull(CartAdditionLedger(context).lookup(basic))
    }

    @Test fun failedMigrationNeverReportsSuccessAndNeverDeletesLegacyReceipt() {
        val ledger = CartAdditionLedger(context)
        assertTrue(ledger.claim(exact, "old-owner"))
        val legacy = downgradeToLegacy(exact).toString()
        assertFalse(CartAdditionLedger(contextWithFailedCommits()).migrateKnownUrls(listOf(exact)))
        assertEquals(legacy, preferences.getString(digest(exact), null))
        expectInvalid { CartAdditionLedger(context).claim(basic, "unsafe-basic") }
    }

    @Test fun concurrentBasicAndExactClaimsAllowOnlyOneExternalAttempt() {
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        try {
            val futures = listOf(basic, exact).mapIndexed { index, url -> executor.submit<Boolean> {
                ready.countDown()
                check(go.await(10, TimeUnit.SECONDS))
                CartAdditionLedger(context).claim(url, "racing-$index")
            } }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            assertEquals(1, futures.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1, preferences.all.size)
            assertNotNull(CartAdditionLedger(context).lookup(basic))
        } finally {
            go.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun failedBasicClearBlocksExactRetryAcrossInstancesUntilExplicitSuccessfulClear() {
        val ledger = CartAdditionLedger(context)
        // No receipt is required: a failed clear remains a failure even for an absent key.
        assertFalse(CartAdditionLedger(contextWithFailedCommits()).clear(basic))
        expectInvalid { CartAdditionLedger(context).lookup(exact) }
        expectInvalid { CartAdditionLedger(context).claim(exact, "unsafe-retry") }
        assertTrue(ledger.clear(basic))
        assertTrue(CartAdditionLedger(context).claim(exact, "safe-after-clear"))
    }

    @Test fun versionTwoReceiptMustMatchItsStoredCanonicalHash() {
        val ledger = CartAdditionLedger(context)
        assertTrue(ledger.claim(exact, "owner"))
        val key = digest(exact)
        val tampered = JSONObject(preferences.getString(key, null)!!).put("canonicalUrl", otherProduct)
        assertTrue(preferences.edit().putString(key, tampered.toString()).commit())
        expectInvalid { ledger.lookup(exact) }
        expectInvalid { ledger.lookup(basic) }
    }

    private fun downgradeToLegacy(url: String): JSONObject {
        val key = digest(url)
        val legacy = JSONObject(preferences.getString(key, null)!!).apply {
            put("version", 1)
            remove("canonicalUrl")
        }
        assertTrue(preferences.edit().putString(key, legacy.toString()).commit())
        return legacy
    }

    private fun digest(canonical: String): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun expectInvalid(action: () -> Unit) {
        try {
            action()
            fail("Unresolved or damaged receipts must not permit another addition")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        } catch (_: org.json.JSONException) {
        } catch (_: ClassCastException) {
        }
    }

    private fun contextWithFailedCommits(): Context {
        val failed = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "edit") failingEditor(preferences.edit())
            else method.invoke(preferences, *(args ?: emptyArray()))
        } as SharedPreferences
        return object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = failed
        }
    }

    private fun failingEditor(delegate: SharedPreferences.Editor): SharedPreferences.Editor {
        lateinit var proxy: SharedPreferences.Editor
        proxy = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            if (method.name == "commit") false
            else {
                val result = method.invoke(delegate, *(args ?: emptyArray()))
                if (result is SharedPreferences.Editor) proxy else result
            }
        } as SharedPreferences.Editor
        return proxy
    }
}
