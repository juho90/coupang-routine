package dev.local.rebuy.cart

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CartAdditionLedgerTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private val url = "https://www.coupang.com/vp/products/123456?itemId=333&vendorItemId=444"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = context.getSharedPreferences("cart_addition_ledger_v1", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        assertTrue(CartAdditionLedger(context).clear(url))
    }

    @Test fun claimSurvivesReopeningAndCanonicalUrlSpellingChanges() {
        val ledger = CartAdditionLedger(context)
        assertNull(ledger.lookup(url))
        assertTrue(ledger.claim(url, "first"))
        val record = requireNotNull(ledger.lookup(url))
        assertEquals(CartAdditionLedger.Status.CLAIMED, record.status)
        assertTrue(record.atEpochMillis > 0)
        val canonicalEquivalent = "https://m.coupang.com/vp/products/123456?sourceType=share&vendorItemId=444&itemId=333"
        val reopened = CartAdditionLedger(context)
        assertEquals(record, reopened.lookup(canonicalEquivalent))
        assertFalse(reopened.claim(canonicalEquivalent, "second"))
        assertEquals(1, preferences.all.size)
        assertTrue(preferences.all.keys.single().matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun concurrentInstancesOnlyClaimOneAttempt() {
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        try {
            val futures = (1..8).map { index -> executor.submit<Boolean> {
                val ledger = CartAdditionLedger(context)
                ready.countDown()
                check(go.await(10, TimeUnit.SECONDS))
                ledger.claim(url, "attempt-$index")
            } }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            assertEquals(1, futures.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(CartAdditionLedger.Status.CLAIMED, CartAdditionLedger(context).lookup(url)?.status)
        } finally {
            go.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun differentExactOptionsHaveIndependentRecords() {
        val ledger = CartAdditionLedger(context)
        val anotherOption = url.replace("itemId=333", "itemId=334")
        val anotherSeller = url.replace("vendorItemId=444", "vendorItemId=445")
        assertTrue(ledger.claim(url, "one"))
        assertNull(ledger.lookup(anotherOption))
        assertTrue(ledger.claim(anotherOption, "two"))
        assertTrue(ledger.claim(anotherSeller, "three"))
        assertEquals(3, preferences.all.size)
    }

    @Test fun confirmationRequiresTheClaimingAttemptAndCannotConfirmTwice() {
        val ledger = CartAdditionLedger(context)
        assertFalse(ledger.confirm(url, "none"))
        assertTrue(ledger.claim(url, "first"))
        val claimed = requireNotNull(ledger.lookup(url))
        assertFalse(ledger.confirm(url, "another"))
        assertEquals(claimed, ledger.lookup(url))
        assertTrue(ledger.confirm(url, "first"))
        assertFalse(ledger.confirm(url, "first"))
        val reopened = CartAdditionLedger(context)
        assertEquals(claimed.copy(status = CartAdditionLedger.Status.CONFIRMED), reopened.lookup(url))
        assertFalse(reopened.claim(url, "later"))
    }

    @Test fun clearingIsExplicitAndOnlyRemovesThatOption() {
        val ledger = CartAdditionLedger(context)
        val other = url.replace("vendorItemId=444", "vendorItemId=445")
        assertTrue(ledger.claim(url, "first"))
        assertTrue(ledger.claim(other, "other"))
        assertTrue(ledger.clear("https://www.coupang.com/vp/products/123456"))
        assertNotNull(ledger.lookup(url))
        assertTrue(ledger.clear(url))
        assertNull(ledger.lookup(url))
        assertNotNull(ledger.lookup(other))
        assertTrue(ledger.claim(url, "after-manual-check"))
    }

    @Test fun failedCommitsNeverReportSuccessfulClaimsConfirmationsOrClears() {
        val normal = CartAdditionLedger(context)
        val broken = CartAdditionLedger(contextWithFailedCommits())
        assertFalse(broken.claim(url, "not-persisted"))
        assertTrue(normal.claim(url, "first"))
        assertFalse(broken.confirm(url, "first"))
        assertEquals(CartAdditionLedger.Status.CLAIMED, normal.lookup(url)?.status)
        assertFalse(broken.clear(url))
        expectInvalid { normal.lookup(url) }
        expectInvalid { normal.claim(url, "unsafe-retry") }
        assertTrue(normal.clear(url))
        assertNull(normal.lookup(url))
        assertTrue(normal.claim(url, "after-successful-clear"))
    }

    @Test fun malformedUnknownAndUnsupportedRecordsBlockReadsAndClaims() {
        val ledger = CartAdditionLedger(context)
        assertTrue(ledger.claim(url, "first"))
        val key = preferences.all.keys.single()
        val original = preferences.getString(key, null)!!
        val invalidRecords = listOf(
            "broken-json",
            JSONObject(original).put("version", 3).toString(),
            JSONObject(original).put("status", "UNRECOGNIZED").toString(),
            JSONObject(original).put("atEpochMillis", "100").toString(),
            JSONObject(original).put("atEpochMillis", -1).toString(),
            JSONObject(original).apply { remove("attemptId") }.toString(),
            JSONObject(original).put("extra", true).toString(),
        )
        for (invalid in invalidRecords) {
            assertTrue(preferences.edit().putString(key, invalid).commit())
            expectInvalid { ledger.lookup(url) }
            expectInvalid { ledger.claim(url, "another") }
            expectInvalid { ledger.confirm(url, "first") }
        }
        assertTrue(ledger.clear(url))
        assertNull(ledger.lookup(url))
    }

    @Test fun nonStringStoredValueIsNotTreatedAsMissing() {
        val ledger = CartAdditionLedger(context)
        assertTrue(ledger.claim(url, "first"))
        val key = preferences.all.keys.single()
        assertTrue(preferences.edit().putInt(key, 1).commit())
        expectInvalid { ledger.lookup(url) }
        expectInvalid { ledger.claim(url, "another") }
        assertTrue(ledger.clear(url))
        assertNull(ledger.lookup(url))
    }

    @Test fun partialLinksAndInvalidAttemptIdsCannotClaim() {
        val ledger = CartAdditionLedger(context)
        for (invalid in listOf("https://www.coupang.com/vp/products/123456?itemId=333", "https://link.coupang.com/a/example")) {
            expectInvalid { ledger.lookup(invalid) }
            expectInvalid { ledger.claim(invalid, "attempt") }
            assertFalse(ledger.clear(invalid))
        }
        for (invalid in listOf("", " ", "x".repeat(101), "attempt\n")) {
            expectInvalid { ledger.claim(url, invalid) }
            expectInvalid { ledger.confirm(url, invalid) }
        }
        assertTrue(preferences.all.isEmpty())
    }

    private fun expectInvalid(action: () -> Unit) {
        try {
            action()
            fail("Invalid ledger data must not permit another addition")
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
