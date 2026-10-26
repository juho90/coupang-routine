package dev.local.rebuy

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ProductLauncherTest {
    private val exactUrl =
        "https://www.coupang.com/vp/products/7157272883?itemId=21389601649&vendorItemId=91294601197"
    private val nativeUri =
        "coupang://product?pId=7157272883&itemId=21389601649&vendorItemId=91294601197"

    @Test
    fun sharedLinkIsDispatchedUnchangedWithoutResolvingItsRedirect() {
        val context = CapturingContext()
        val sharedUrl = "https://link.coupang.com/a/h1yJnHQWjY"

        assertTrue(ProductLauncher.open(context, sharedUrl))

        val intent = context.intents.single()
        assertEquals(sharedUrl, intent.dataString)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(intent.`package`)
        assertFalse((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun exactProductOpensItsSelectedOptionInTheCoupangPackageFirst() {
        val context = CapturingContext()

        assertTrue(ProductLauncher.open(context, exactUrl))

        val intent = context.intents.single()
        assertEquals(nativeUri, intent.dataString)
        assertEquals("com.coupang.mobile", intent.`package`)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertFalse((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun unavailableNativeActivityFallsBackToTheOriginalHttpsUrl() {
        val context = CapturingContext(rejectNative = true)
        val trackedUrl = "$exactUrl&sourceType=cmgoms#details"

        assertTrue(ProductLauncher.open(context, trackedUrl))

        assertEquals(listOf(nativeUri, trackedUrl), context.intents.map { it.dataString })
        assertEquals("com.coupang.mobile", context.intents.first().`package`)
        assertNull(context.intents.last().`package`)
        assertTrue(context.intents.all { (it.flags and Intent.FLAG_ACTIVITY_NEW_TASK) == 0 })
    }

    @Test
    fun invalidUrlsDoNotLaunchAnyActivity() {
        val context = CapturingContext()
        listOf(
            "http://www.coupang.com/vp/products/1",
            "https://coupang.com.attacker.example/vp/products/1",
            "https://www.coupang.com/",
            "file:///sdcard/product.png",
        ).forEach { url -> assertFalse("Accepted $url", ProductLauncher.open(context, url)) }

        assertTrue(context.intents.isEmpty())
    }

    @Test
    fun newTaskIsAppliedToBothNativeAndHttpsFallbackAttempts() {
        val context = CapturingContext(rejectNative = true)

        assertTrue(ProductLauncher.open(context, exactUrl, newTask = true))

        assertEquals(listOf(nativeUri, exactUrl), context.intents.map { it.dataString })
        assertTrue(context.intents.all { (it.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0 })
    }

    @Test
    fun cartLaunchUsesOnlyTheNativeCoupangProductRouteWithObservedPartialIds() {
        val context = CapturingContext()
        assertTrue(ProductLauncher.openInCoupang(
            context,
            "https://www.coupang.com/vp/products/9750360880?itemId=29195429057",
            newTask = true,
        ))
        val intent = context.intents.single()
        assertEquals("coupang://product?pId=9750360880&itemId=29195429057", intent.dataString)
        assertEquals("com.coupang.mobile", intent.`package`)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun cartLaunchFailureDoesNotFallBackToAWebBrowser() {
        val context = CapturingContext(rejectNative = true)
        assertFalse(ProductLauncher.openInCoupang(context, exactUrl))
        assertEquals(1, context.intents.size)
        assertEquals("com.coupang.mobile", context.intents.single().`package`)
        assertEquals(nativeUri, context.intents.single().dataString)
    }

    @Test
    fun unresolvedShareLinkCannotStartCartLaunch() {
        val context = CapturingContext()
        assertFalse(ProductLauncher.openInCoupang(context, "https://link.coupang.com/a/h4NNZza6N2"))
        assertTrue(context.intents.isEmpty())
    }

    /** Captures dispatch only: neither Robolectric nor the device launches an external app. */
    private class CapturingContext(private val rejectNative: Boolean = false) :
        ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        val intents = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            intents += Intent(intent)
            if (rejectNative && intent.`package` == "com.coupang.mobile") {
                throw ActivityNotFoundException("Native activity unavailable in this test")
            }
        }
    }
}
