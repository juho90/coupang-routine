package dev.local.rebuy.domain

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.net.URI

/** Resolves navigation in a visible WebView. The owner must close the handle before destroying it. */
object CoupangShareLinkResolver {
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun start(
        webView: WebView,
        rawUrl: String,
        onResolved: (String) -> Unit,
        onFailure: (String) -> Unit,
    ): Closeable {
        check(Looper.myLooper() == Looper.getMainLooper())
        val handler = Handler(Looper.getMainLooper())
        val policy = NavigationPolicy()
        var finished = false
        lateinit var timeout: Runnable
        lateinit var visibilityCheck: Runnable

        fun stop() {
            handler.removeCallbacks(timeout)
            handler.removeCallbacks(visibilityCheck)
            webView.stopLoading()
        }
        fun fail(message: String) {
            if (finished) return
            finished = true
            stop()
            onFailure(message)
        }
        fun inspect(url: String): Boolean {
            if (finished) return true
            return try {
                val product = policy.visit(url)
                if (product != null) {
                    finished = true
                    stop()
                    onResolved(product.canonicalUrl)
                    true
                } else false
            } catch (_: IllegalArgumentException) {
                fail("공유 링크에서 안전한 쿠팡 상품 주소를 확인하지 못했습니다. 전체 상품 주소를 입력해 주세요.")
                true
            }
        }

        timeout = Runnable { fail("공유 링크 확인 시간이 초과되었습니다. 쿠팡 상품의 전체 주소를 입력해 주세요.") }
        visibilityCheck = object : Runnable {
            override fun run() {
                if (finished) return
                if (webView.isAttachedToWindow && (!webView.isShown || webView.windowVisibility != View.VISIBLE)) {
                    fail("링크 확인 화면이 닫혀 확인을 중단했습니다.")
                    return
                }
                handler.postDelayed(this, 250)
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true // Ordinary page redirects only; there is no JavaScript bridge.
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        // This is a visible navigation preview, not an interactive checkout or login browser.
        webView.setOnTouchListener { _, _ -> true }
        webView.isLongClickable = false
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        }
        webView.setDownloadListener { _, _, _, _, _ -> fail("파일 다운로드 링크는 상품 주소로 사용할 수 없습니다.") }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return true
                if (request.method != "GET") {
                    fail("공유 링크에서 상품 주소를 확인하지 못했습니다.")
                    return true
                }
                return inspect(request.url.toString())
            }

            @Deprecated("Needed for WebView implementations using the legacy callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = inspect(url)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                inspect(url)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val allowed = request.method == "GET" && if (request.isForMainFrame) {
                    runCatching { CoupangUrl.normalize(request.url.toString()) }.isSuccess
                } else NavigationPolicy.allowedResource(request.url.toString())
                if (allowed) return null
                if (request.isForMainFrame) handler.post {
                    if (request.url.scheme == "coupang") inspect(request.url.toString())
                    else fail("쿠팡의 안전한 HTTPS 상품 링크만 확인할 수 있습니다.")
                }
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail("공유 링크에 연결하지 못했습니다. 네트워크와 전체 상품 주소를 확인해 주세요.")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) fail("쿠팡에서 공유 링크 확인을 허용하지 않았습니다. 전체 상품 주소를 입력해 주세요.")
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                fail("보안 연결을 확인하지 못해 공유 링크 확인을 중단했습니다.")
            }

            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                handler.cancel()
                fail("로그인이 필요한 링크는 확인하지 않습니다. 전체 상품 주소를 입력해 주세요.")
            }
        }

        handler.postDelayed(timeout, 20_000)
        handler.postDelayed(visibilityCheck, 250)
        webView.post {
            if (!finished) {
                val normalized = runCatching { CoupangUrl.normalize(rawUrl) }.getOrNull()
                if (normalized == null || !policy.isShareOrProduct(normalized)) {
                    fail("쿠팡 상품 또는 공유 링크를 입력해 주세요.")
                } else if (!inspect(normalized)) webView.loadUrl(normalized)
            }
        }
        return Closeable {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!finished) {
                finished = true
                stop()
            }
        }
    }

    internal class NavigationPolicy {
        private val visited = mutableSetOf<String>()
        private var current: String? = null

        fun isShareOrProduct(raw: String): Boolean {
            val url = runCatching { CoupangUrl.normalize(raw) }.getOrNull() ?: return false
            return runCatching { CoupangCartProductLink.parse(url) }.isSuccess || URI(url).host == "link.coupang.com"
        }

        fun visit(raw: String): CoupangCartProductLink? {
            val product: CoupangCartProductLink?
            val url: String
            if (raw.startsWith("coupang:")) {
                product = CoupangCartProductLink.fromObservedNative(raw)
                url = product.canonicalUrl
            } else {
                url = CoupangUrl.normalize(raw)
                product = runCatching { CoupangCartProductLink.parse(url) }.getOrNull()
            }
            if (url == current) return product // WebView reports one navigation in two callbacks.
            // One initial page plus at most six navigation hops, including the product destination.
            require(visited.size < 7 && visited.add(url)) { "Too many or cyclic redirects" }
            current = url
            return product
        }

        companion object {
            fun allowedResource(raw: String): Boolean = runCatching {
                val uri = URI(raw)
                val host = uri.host?.lowercase(java.util.Locale.ROOT).orEmpty()
                uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 &&
                    (host == "coupang.com" || host.endsWith(".coupang.com") ||
                        host == "coupangcdn.com" || host.endsWith(".coupangcdn.com"))
            }.getOrDefault(false)
        }
    }
}
