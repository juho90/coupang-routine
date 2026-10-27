package dev.local.rebuy

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.local.rebuy.domain.CoupangCartProductLink
import dev.local.rebuy.domain.CoupangUrl

object ProductLauncher {
    /** Cart automation can observe only Coupang: never report a browser launch as success. */
    fun openInCoupang(context: Context, rawUrl: String, newTask: Boolean = false): Boolean {
        val product = runCatching { CoupangCartProductLink.parse(rawUrl) }.getOrNull() ?: return false
        return launch(context, product.nativeUri, "com.coupang.mobile", newTask)
    }

    fun open(context: Context, rawUrl: String, newTask: Boolean = false): Boolean {
        val url = runCatching { CoupangUrl.normalize(rawUrl) }.getOrNull() ?: return false
        val product = runCatching { CoupangCartProductLink.parseExactOption(url) }.getOrNull()
        if (product != null && launch(context, product.nativeUri, "com.coupang.mobile", newTask)) return true
        return launch(context, url, null, newTask)
    }

    private fun launch(context: Context, url: String, packageName: String?, newTask: Boolean): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            packageName?.let(::setPackage)
            if (newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
