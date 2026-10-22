package dev.local.rebuy.cart

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.local.rebuy.autobuy.AutoBuyController

/** Explicit cart addition or checkout preview. Payment and authentication actions are unavailable. */
class CartAssistService : AccessibilityService() {
    private var inspecting = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        CartAssistController.connected(this)
        AutoBuyController.connected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() == CartAssistController.COUPANG_PACKAGE) inspectCurrentWindow()
    }

    override fun onInterrupt() {
        CartAssistController.cancel()
        AutoBuyController.cancel()
    }

    override fun onDestroy() {
        CartAssistController.disconnected(this)
        AutoBuyController.disconnected(this)
        super.onDestroy()
    }

    internal fun inspectCurrentWindow() {
        if (AutoBuyController.isActive()) {
            AutoBuyController.inspectWindow(rootInActiveWindow)
            return
        }
        val current = CartAssistController.currentSession() ?: return
        if (inspecting) return
        inspecting = true
        try {
            val root = rootInActiveWindow ?: return
            val now = SystemClock.elapsedRealtime()
            // Only package metadata is inspected outside Coupang; no outside text or nodes are read.
            if (root.packageName?.toString() != CartAssistController.COUPANG_PACKAGE) {
                if (current.coupangSeen && now - current.startedAt > 1_500) CartAssistController.cancel()
                return
            }
            current.coupangSeen = true
            val scanned = scan(root)
            if (current.clickClaimed) {
                if (CartAssistPolicy.hasConfirmedAddition(scanned.map { it.second })) {
                    CartAssistController.finish("쿠팡의 담기 완료 안내를 확인했습니다. 장바구니에 1개를 추가했습니다.", true)
                }
                return
            }
            // Allow the native link to replace the previous detail page before considering a click.
            if (now - current.startedAt < 1_800) return
            val nodes = scanned.map { it.second }
            val index = CartAssistPolicy.cartTargetIndex(nodes, current.request.expectedTitle, current.request.expectedOption)
            if (index != null) {
                if (current.identitySeenAt == 0L) {
                    current.identitySeenAt = now
                    return
                }
                if (now - current.identitySeenAt < 500) return
                if (!CartAssistController.claimClick(current)) return
                if (!scanned[index].first.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    CartAssistController.finish("담기 버튼을 한 번 요청했지만 결과를 확인하지 못했습니다. 장바구니를 직접 확인해 주세요.", false)
                }
                return
            }
            current.identitySeenAt = 0
            if (CartAssistPolicy.isDetailScreen(nodes) && current.scrollCount < 4 && now - current.lastScrollAt >= 1_200) {
                val scroller = scanned.firstOrNull { (node, snapshot) ->
                    snapshot.visible && snapshot.enabled && snapshot.resourceId in CartAssistPolicy.SCROLL_IDS && node.isScrollable
                }?.first ?: return
                current.scrollCount += 1
                current.lastScrollAt = now
                // Semantic scrolling on an inspected product container only; never screen coordinates.
                scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
        } catch (_: RuntimeException) {
            CartAssistController.finish("쿠팡 화면을 확인하는 중 멈췄습니다. 장바구니에서 결과를 확인해 주세요.", false)
        } finally {
            inspecting = false
        }
    }

    private fun scan(root: AccessibilityNodeInfo): List<Pair<AccessibilityNodeInfo, CartAssistPolicy.Node>> {
        val result = mutableListOf<Pair<AccessibilityNodeInfo, CartAssistPolicy.Node>>()
        fun visit(node: AccessibilityNodeInfo, parent: Int?, ancestors: List<String>, depth: Int) {
            if (result.size >= 600 || depth > 35) return
            if (node.packageName?.toString() != CartAssistController.COUPANG_PACKAGE) return
            val index = result.size
            val id = node.viewIdResourceName.orEmpty()
            result += node to CartAssistPolicy.Node(
                text = if (node.isPassword) "" else node.text?.toString().orEmpty(),
                resourceId = id,
                parentIndex = parent,
                ancestorIds = ancestors,
                visible = node.isVisibleToUser,
                enabled = node.isEnabled,
                clickable = node.isClickable,
                childCount = node.childCount,
            )
            for (childIndex in 0 until node.childCount) {
                val child = node.getChild(childIndex) ?: continue
                visit(child, index, if (id.isEmpty()) ancestors else ancestors + id, depth + 1)
            }
        }
        visit(root, null, emptyList(), 0)
        return result
    }
}
