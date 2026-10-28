package dev.local.rebuy.cart

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.local.rebuy.autobuy.AutoBuyController
import dev.local.rebuy.importproduct.ProductLinkImportController

class CartAssistService : AccessibilityService() {
    private var inspecting = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        CartAssistController.connected(this)
        AutoBuyController.connected(this)
        ProductLinkImportController.connected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() == CartAssistController.COUPANG_PACKAGE) inspectCurrentWindow()
    }

    override fun onInterrupt() {
        CartAssistController.interrupted()
        AutoBuyController.cancel()
        ProductLinkImportController.cancel()
    }

    override fun onDestroy() {
        CartAssistController.disconnected(this)
        AutoBuyController.disconnected(this)
        ProductLinkImportController.disconnected(this)
        super.onDestroy()
    }

    internal fun inspectCurrentWindow() {
        if (ProductLinkImportController.isActive()) {
            ProductLinkImportController.inspectWindow(rootInActiveWindow)
            return
        }
        if (AutoBuyController.isActive()) {
            AutoBuyController.inspectWindow(rootInActiveWindow)
            return
        }
        val current = CartAssistController.currentSession() ?: return
        if (inspecting) return
        inspecting = true
        val handles = mutableListOf<AccessibilityNodeInfo>()
        try {
            val root = rootInActiveWindow?.also(handles::add) ?: run {
                CartAssistController.reportStage(current, CartAssistStage.NO_ACTIVE_WINDOW)
                return
            }
            val now = SystemClock.elapsedRealtime()
            // Only package metadata is inspected outside Coupang; no outside text or nodes are read.
            if (root.packageName?.toString() != CartAssistController.COUPANG_PACKAGE) {
                if (current.coupangSeen && now - current.startedAt > 1_500) {
                    CartAssistController.reportStage(current, CartAssistStage.OUTSIDE_COUPANG)
                    CartAssistController.finish(if (current.clickClaimed)
                        "쿠팡 화면을 벗어나 중지했습니다. 장바구니에서 결과를 직접 확인해 주세요."
                        else "쿠팡 화면을 벗어나 점검을 중지했습니다. 담기 버튼은 누르지 않았습니다.", false)
                } else CartAssistController.reportStage(current, CartAssistStage.WAITING_NATIVE_APP)
                return
            }
            current.coupangSeen = true
            val scan = scan(root, handles)
            val scanned = scan.nodes
            val nodes = scanned.map { it.second }
            CartAssistController.reportScan(current, nodes.size, nodes.count { it.resourceId.isNotEmpty() },
                scan.truncated, CartAssistPolicy.detailStructure(nodes))
            if (current.clickClaimed) {
                // A read-only session can never accept a previous addition popup as its result.
                if (current.request.verifyOnly) {
                    CartAssistController.finish("담기 전 점검의 안전 조건이 달라져 중지했습니다. 담기 버튼은 누르지 않았습니다.", false)
                    return
                }
                CartAssistController.reportStage(current, CartAssistStage.WAITING_CONFIRMATION)
                if (CartAssistPolicy.hasConfirmedAddition(nodes)) {
                    CartAssistController.reportStage(current, CartAssistStage.CONFIRMED)
                    CartAssistController.finish("쿠팡의 담기 완료 안내를 확인했습니다. 장바구니에 1개를 추가했습니다.", true)
                }
                return
            }
            // Allow the native link to replace the previous detail page before considering a click.
            if (now - current.startedAt < 1_800) {
                CartAssistController.reportStage(current, CartAssistStage.OPENING_PRODUCT)
                return
            }
            val inspection = CartAssistPolicy.inspectCartTarget(nodes, current.request.expectedTitle, current.request.expectedOption)
            val stage = when {
                !inspection.detailScreen -> CartAssistStage.WAITING_DETAIL_SCREEN
                !inspection.titleMatched -> CartAssistStage.WAITING_TITLE
                !inspection.optionMatched -> CartAssistStage.WAITING_OPTION
                !inspection.buttonMatched -> CartAssistStage.WAITING_CART_BUTTON
                else -> CartAssistStage.IDENTITY_STABILIZING
            }
            CartAssistController.reportStage(current, stage, inspection)
            val index = inspection.targetIndex
            if (index != null) {
                if (current.identitySeenAt == 0L) {
                    current.identitySeenAt = now
                    return
                }
                if (now - current.identitySeenAt < 500) return
                if (current.request.verifyOnly) {
                    CartAssistController.finishVerified(current)
                    return
                }
                if (!CartAssistController.checkPrice(current, nodes, scan.truncated)) return
                if (!CartAssistController.claimClick(current)) return
                // A second independent mode gate protects the external effect even if claimClick
                // is changed incorrectly later. Verification never reaches ACTION_CLICK.
                val accepted = current.performClaimedAction { scanned[index].first.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                CartAssistController.reportClickResult(current)
                if (!accepted) {
                    CartAssistController.finish("담기 버튼 누르기 요청이 수락되지 않았습니다. 자동으로 다시 누르지 않습니다. 장바구니에서 결과를 확인해 주세요.", false)
                }
                return
            }
            current.identitySeenAt = 0
            if (inspection.detailScreen && current.scrollCount < 4 && now - current.lastScrollAt >= 1_200) {
                // Throttle even rejected requests, and spend the bound only on accepted scrolls.
                current.lastScrollAt = now
                val accepted = CartAssistPolicy.requestDetailScroll(nodes,
                    isScrollable = { scanned[it].first.isScrollable },
                    scrollForward = { scanned[it].first.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) },
                )
                if (accepted) {
                    current.scrollCount += 1
                    CartAssistController.reportStage(current, CartAssistStage.SCROLLING_DETAIL, inspection)
                }
            }
        } catch (_: Exception) {
            CartAssistController.finish(if (current.clickClaimed)
                "쿠팡 화면을 확인하는 중 멈췄습니다. 장바구니에서 결과를 확인해 주세요."
                else "쿠팡 화면을 읽는 중 멈췄습니다. 담기 버튼은 누르지 않았습니다.", false)
        } finally {
            recycleNodes(handles)
            inspecting = false
        }
    }

    private data class ScanResult(val nodes: List<Pair<AccessibilityNodeInfo, CartAssistPolicy.Node>>, val truncated: Boolean)

    private fun scan(root: AccessibilityNodeInfo, handles: MutableList<AccessibilityNodeInfo>): ScanResult {
        val snapshot = CartNodeTraversal.scan(
            root = root,
            accepts = { it.packageName?.toString() == CartAssistController.COUPANG_PACKAGE },
            resourceId = { it.viewIdResourceName.orEmpty() },
            childCount = { it.childCount },
            childAt = { node, index -> node.getChild(index)?.also(handles::add) },
        )
        return ScanResult(snapshot.entries.map { entry ->
            val node = entry.node
            node to CartAssistPolicy.Node(
                text = if (node.isPassword) "" else node.text?.toString().orEmpty(),
                resourceId = entry.resourceId,
                parentIndex = entry.parentIndex,
                ancestorIds = entry.ancestorIds,
                visible = node.isVisibleToUser,
                enabled = node.isEnabled,
                clickable = node.isClickable,
                childCount = entry.childCount,
            )
        }, snapshot.truncated)
    }

    @Suppress("DEPRECATION")
    private fun recycleNodes(handles: List<AccessibilityNodeInfo>) {
        handles.forEach { runCatching { it.recycle() } }
    }
}
