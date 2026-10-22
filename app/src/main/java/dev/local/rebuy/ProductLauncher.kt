package dev.local.rebuy

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.local.rebuy.domain.CoupangProductLink
import dev.local.rebuy.domain.CoupangUrl

/** The public product deep link observed in Coupang 9.4.5; HTTPS remains the fallback. */
object ProductLauncher {
    fun open(context: Context, rawUrl: String): Boolean {
        val url = runCatching { CoupangUrl.normalize(rawUrl) }.getOrNull() ?: return false
        val product = runCatching { CoupangProductLink.parse(url) }.getOrNull()
        if (product != null && launch(context, product.nativeUri, "com.coupang.mobile")) return true
        return launch(context, url, null)
    }

    private fun launch(context: Context, url: String, packageName: String?): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            packageName?.let(::setPackage)
        })
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}
