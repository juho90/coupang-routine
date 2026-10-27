package dev.local.rebuy.importproduct

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import dev.local.rebuy.MainActivity
import dev.local.rebuy.ProductLauncher
import dev.local.rebuy.autobuy.AutoBuyController
import dev.local.rebuy.cart.CartAssistController
import dev.local.rebuy.cart.CartAssistPolicy
import dev.local.rebuy.cart.CartAssistService
import dev.local.rebuy.cart.CartBatchController
import dev.local.rebuy.cart.CartNodeTraversal
import dev.local.rebuy.domain.CoupangCartProductLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ProductLinkImportState {
    data object Idle : ProductLinkImportState
    data class Running(val message: String) : ProductLinkImportState
    data class Finished(val productUrl: String, val suggestions: ProductTextSuggestions?, val message: String) : ProductLinkImportState
}

/** Explicit foreground reading only. No click, purchase, storage, or automatic restart exists here. */
object ProductLinkImportController {
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow<ProductLinkImportState>(ProductLinkImportState.Idle)
    val state: StateFlow<ProductLinkImportState> = mutableState.asStateFlow()
    private var service: CartAssistService? = null
    private var current: Session? = null
    private var inspecting = false

    private class Session(val context: Context, val url: String) {
        val startedAt = SystemClock.elapsedRealtime()
        var coupangSeen = false
        var scrollCount = 0
        var lastScrollAt = 0L
        var candidateSince = 0L
        var candidate: ProductTextSuggestions? = null
    }

    fun isActive(): Boolean = current != null

    fun start(context: Context, resolvedFullUrl: String): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (isActive()) return false
        val url = runCatching { CoupangCartProductLink.parse(resolvedFullUrl).canonicalUrl }.getOrNull()
            ?: return reject(resolvedFullUrl, "옵션 식별자가 포함된 쿠팡 상품 주소를 먼저 확인해 주세요.")
        if (CartAssistController.currentSession() != null || AutoBuyController.isActive() || CartBatchController.isRunning()) {
            return reject(url, "다른 구매 도우미가 실행 중입니다. 실행을 마친 뒤 정보를 불러와 주세요.")
        }
        if (!CartAssistController.isEnabled(context)) return reject(url, "Android 접근성 설정에서 다시담기 구매 도우미를 켜 주세요.")
        if (service == null) return reject(url, "접근성 권한은 켜져 있지만 도우미가 연결되지 않았습니다. 잠시 기다린 뒤 다시 시도해 주세요.")
        val session = Session(context.applicationContext, url)
        current = session
        mutableState.value = ProductLinkImportState.Running("쿠팡 상품 화면에서 상품명과 현재 옵션을 읽고 있습니다. 담기·구매 버튼은 누르지 않습니다.")
        return try {
            if (!ProductLauncher.openInCoupang(context, url, newTask = true)) {
                finish(null, "쿠팡 상품 화면을 열지 못했습니다. 주소와 쿠팡 앱 설치를 확인해 주세요.")
                false
            } else {
                handler.postDelayed(timeout, 25_000)
                handler.postDelayed(poll, 500)
                true
            }
        } catch (_: RuntimeException) {
            finish(null, "쿠팡 상품 화면을 열지 못했습니다.")
            false
        }
    }

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        finish(null, "상품 정보 읽기를 중단했습니다. 등록 정보는 변경하지 않았습니다.")
    }

    internal fun connected(service: CartAssistService) { this.service = service }

    internal fun disconnected(service: CartAssistService) {
        if (this.service !== service) return
        finish(null, "접근성 서비스 연결이 끊겨 상품 정보 읽기를 중단했습니다.")
        this.service = null
    }

    private val timeout = Runnable {
        val candidate = current?.candidate?.takeIf { it.titleCandidates.isNotEmpty() }
        finish(candidate, if (candidate == null) "쿠팡 상세 화면에서 상품 정보를 읽지 못했습니다. 이미지에서 읽기나 직접 입력을 사용해 주세요."
            else "일부 상품 정보만 읽었습니다. 링크의 상품명·옵션을 직접 확인한 뒤 적용해 주세요.", returnToApp = candidate != null)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!isActive()) return
            service?.inspectCurrentWindow()
            if (isActive()) handler.postDelayed(this, 500)
        }
    }

    internal fun inspectWindow(root: AccessibilityNodeInfo?) {
        val handles = mutableListOf<AccessibilityNodeInfo>()
        root?.let(handles::add)
        if (inspecting) { recycle(handles); return }
        inspecting = true
        try {
            val session = current ?: return
            if (root == null) return
            val now = SystemClock.elapsedRealtime()
            // No text or child nodes from another package are inspected.
            if (root.packageName?.toString() != CartAssistController.COUPANG_PACKAGE) {
                if (session.coupangSeen) finish(null, "쿠팡을 벗어나 상품 정보 읽기를 중단했습니다.")
                return
            }
            session.coupangSeen = true
            if (now - session.startedAt < 1_800) return
            val snapshot = CartNodeTraversal.scan(root,
                accepts = { it.packageName?.toString() == CartAssistController.COUPANG_PACKAGE },
                resourceId = { it.viewIdResourceName.orEmpty() }, childCount = { it.childCount },
                childAt = { node, index -> node.getChild(index)?.also(handles::add) },
            )
            val nodes = snapshot.entries.map { entry ->
                val node = entry.node
                val detailText = ProductLinkImportPolicy.inDetailContent(entry.ancestorIds)
                val buttonLabel = entry.resourceId == CartAssistPolicy.CART_TEXT_ID &&
                    CartAssistPolicy.BOTTOM_BUTTONS_ID in entry.ancestorIds
                CartAssistPolicy.Node(
                    text = if (!node.isPassword && (detailText || buttonLabel)) node.text?.toString().orEmpty() else "",
                    resourceId = entry.resourceId, parentIndex = entry.parentIndex, ancestorIds = entry.ancestorIds,
                    visible = node.isVisibleToUser, enabled = node.isEnabled, clickable = node.isClickable,
                    childCount = entry.childCount,
                )
            }
            val suggestions = ProductLinkImportPolicy.suggest(nodes) ?: return
            val hasTitle = suggestions.titleCandidates.isNotEmpty()
            if (hasTitle) {
                val previous = session.candidate
                if (previous == null || previous.titleCandidates != suggestions.titleCandidates || previous.optionCandidates != suggestions.optionCandidates) {
                    session.candidate = suggestions
                    session.candidateSince = now
                } else if (suggestions.optionCandidates.isNotEmpty() && now - session.candidateSince >= 500) {
                    finish(suggestions, "쿠팡 화면에서 읽은 후보입니다. 링크의 상품명과 옵션이 맞는지 확인한 뒤 적용해 주세요.", returnToApp = true)
                    return
                }
                // Let one more scan confirm the candidate before changing the viewport.
                if (suggestions.optionCandidates.isNotEmpty()) return
            }
            if (session.scrollCount < 4 && now - session.lastScrollAt >= 1_200) {
                session.lastScrollAt = now
                // The only native action allowed by this reader is bounded detail scrolling.
                val accepted = CartAssistPolicy.requestDetailScroll(nodes,
                    isScrollable = { snapshot.entries[it].node.isScrollable },
                    scrollForward = { snapshot.entries[it].node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) },
                )
                if (accepted) session.scrollCount += 1
            }
        } catch (_: RuntimeException) {
            finish(null, "쿠팡 화면을 읽지 못했습니다. 등록 정보는 변경하지 않았습니다.")
        } finally {
            recycle(handles)
            inspecting = false
        }
    }

    private fun reject(url: String, message: String): Boolean {
        mutableState.value = ProductLinkImportState.Finished(url, null, message)
        return false
    }

    private fun finish(suggestions: ProductTextSuggestions?, message: String, returnToApp: Boolean = false) {
        val session = current ?: return
        current = null
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(poll)
        mutableState.value = ProductLinkImportState.Finished(session.url, suggestions, message)
        if (returnToApp) runCatching {
            session.context.startActivity(Intent(session.context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    @Suppress("DEPRECATION")
    private fun recycle(handles: List<AccessibilityNodeInfo>) {
        handles.forEach { runCatching { it.recycle() } }
    }
}

/** Text suggestions stay inside the observed product detail; no catalog or option IDs are invented. */
internal object ProductLinkImportPolicy {
    private val detailNotice = Regex(
        "^(?:한\\s*달간\\s*[0-9,]+(?:만|천)?\\s*명.*구매.*|" +
            "(?:오늘|내일|모레)(?:\\([^)]*\\))?\\s*(?:새벽|오전|오후|[0-9]{1,2}시).*|" +
            "[0-9]+\\s*일\\s*남음|구매\\s*[0-9]+\\s*위|브랜드\\s*샵)$",
    )
    fun inDetailContent(ancestors: List<String>): Boolean = ancestors.any { it in CartAssistPolicy.DETAIL_CONTENT_IDS } &&
        ancestors.none { it.contains("recommend", ignoreCase = true) }

    fun suggest(nodes: List<CartAssistPolicy.Node>): ProductTextSuggestions? {
        if (!CartAssistPolicy.isDetailScreen(nodes)) return null
        return ProductTextParser.parse(nodes.asSequence()
            .filter { it.visible && inDetailContent(it.ancestorIds) }
            .map { it.text }.filter { it.isNotBlank() && !detailNotice.matches(it.trim()) }.take(200).toList())
    }
}
