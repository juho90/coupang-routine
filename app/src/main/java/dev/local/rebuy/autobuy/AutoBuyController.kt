package dev.local.rebuy.autobuy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import dev.local.rebuy.cart.CartAssistController
import dev.local.rebuy.cart.CartAssistPolicy
import dev.local.rebuy.cart.CartAssistService
import dev.local.rebuy.data.RoutineItem
import dev.local.rebuy.domain.CoupangProductLinks
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

sealed interface AutoBuyRuntimeState {
    data object Idle : AutoBuyRuntimeState
    data class Running(val message: String) : AutoBuyRuntimeState
    data class Finished(val outcome: AutoBuyOutcome) : AutoBuyRuntimeState
}

/** Foreground preview only. This class deliberately contains no payment-click implementation. */
object AutoBuyController {
    const val checkoutAdapterVerified = false
    private const val TIMEOUT_MS = 60_000L
    private const val PREFIX = "com.coupang.mobile:id/"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow<AutoBuyRuntimeState>(AutoBuyRuntimeState.Idle)
    val state: StateFlow<AutoBuyRuntimeState> = mutableState.asStateFlow()
    private var service: CartAssistService? = null
    private var current: Session? = null
    private var dryRunJob: Job? = null
    private val verifiedPreviewRevisions = mutableSetOf<String>()
    private var inspecting = false

    private data class Session(
        val context: Context,
        val item: RoutineItem,
        val profile: AutoBuyProfile,
        val attemptId: String,
        val continuation: CancellableContinuation<AutoBuyOutcome>,
        val startedAt: Long = SystemClock.elapsedRealtime(),
        var coupangSeen: Boolean = false,
        var actionPending: Boolean = false,
        var checkoutRequested: Boolean = false,
        var checkoutSeen: Boolean = false,
        var identitySeenAt: Long = 0,
        var scrolls: Int = 0,
        var lastScrollAt: Long = 0,
        val totals: MutableSet<Long> = linkedSetOf(),
        var lastCheckoutProblem: String = "주문 상품·수량·최종 금액을 한 번에 검증할 수 없어 멈췄습니다. 결제하지 않았습니다.",
    )

    internal fun isActive(): Boolean = current != null || dryRunJob?.isActive == true
    fun isDryRunVerified(profile: AutoBuyProfile): Boolean = profile.revisionId in verifiedPreviewRevisions

    fun startDryRun(context: Context, item: RoutineItem, profile: AutoBuyProfile): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (isActive() || CartAssistController.currentSession() != null) return false
        verifiedPreviewRevisions.remove(profile.revisionId)
        mutableState.value = AutoBuyRuntimeState.Running("결제 전 확인을 준비하고 있습니다. 실제 구매는 차단돼 있습니다.")
        dryRunJob = scope.launch {
            val store = AutoBuyStore(context)
            var attempt: AutoBuyAttempt? = null
            try {
                attempt = store.claimAttempt(item, profile, true)
                val result = if (attempt == null) AutoBuyOutcome.Blocked("이전 실행 또는 상품 조건을 확인해 주세요. 자동으로 다시 실행하지 않습니다.")
                else execute(context, item, profile, true, attempt.id)
                attempt?.let { store.finishAttempt(it.id, result) }
                mutableState.value = AutoBuyRuntimeState.Finished(result)
            } catch (_: CancellationException) {
                val result = AutoBuyOutcome.Blocked("결제 전 확인을 중지했습니다. 결제하지 않았습니다.")
                withContext(NonCancellable) { attempt?.let { store.finishAttempt(it.id, result) } }
                mutableState.value = AutoBuyRuntimeState.Finished(result)
            } catch (_: Exception) {
                val result = AutoBuyOutcome.Failed("주문 조건 또는 실행 기록을 확인하지 못해 중지했습니다. 결제하지 않았습니다.")
                withContext(NonCancellable) { attempt?.let { runCatching { store.finishAttempt(it.id, result) } } }
                mutableState.value = AutoBuyRuntimeState.Finished(result)
            }
        }
        return true
    }

    suspend fun execute(
        context: Context,
        item: RoutineItem,
        profile: AutoBuyProfile,
        dryRun: Boolean,
        attemptId: String,
    ): AutoBuyOutcome = withContext(Dispatchers.Main.immediate) {
        // User override is a build-time hard gate, evaluated before opening or changing Coupang.
        if (!dryRun) {
            return@withContext AutoBuyOutcome.Blocked(AutoBuyPolicy.PURCHASE_BLOCKED_MESSAGE)
        }
        if (current != null || CartAssistController.currentSession() != null) {
            return@withContext AutoBuyOutcome.Blocked("다른 구매 도우미가 실행 중입니다.")
        }
        if (!CartAssistController.isEnabled(context) || service == null) {
            return@withContext AutoBuyOutcome.Blocked("접근성 설정에서 다시담기 구매 도우미를 직접 켜야 결제 전 화면을 확인할 수 있습니다.")
        }
        val store = AutoBuyStore(context)
        val stored = store.getProfile(item.id)
        val attempt = store.getAttempt(attemptId)
        if (stored != profile || attempt == null || !attempt.dryRun || attempt.itemId != item.id ||
            attempt.cycleId != item.cycleId || attempt.profileRevisionId != profile.revisionId ||
            attempt.status != AutoBuyStatus.CLAIMED || attempt.paymentSubmittedAtEpochMillis != null ||
            attempt.externalMutationAtEpochMillis != null
        ) return@withContext AutoBuyOutcome.Blocked("상품 조건 또는 실행 기록이 변경돼 중지했습니다.")
        try {
            profile.requireMatches(item)
            require(CartAssistPolicy.normalize(profile.expectedTitle).length >= 4)
            require(CartAssistPolicy.normalize(profile.expectedOption).length >= 2)
            require(profile.maxTotalWon in 1..100_000_000)
        } catch (_: IllegalArgumentException) {
            return@withContext AutoBuyOutcome.Blocked("저장된 상품명·옵션·수량·한도를 다시 확인해 주세요.")
        }
        val link = try {
            CoupangProductLinks.resolve(profile.productUrlSnapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext AutoBuyOutcome.Blocked("공유 링크에서 상품 주소를 확인하지 못했습니다. itemId와 vendorItemId가 포함된 전체 상품 주소로 다시 시도해 주세요.")
        }
        suspendCancellableCoroutine<AutoBuyOutcome> { continuation ->
            val session = Session(context.applicationContext, item, profile, attemptId, continuation)
            current = session
            mutableState.value = AutoBuyRuntimeState.Running("쿠팡 상품명과 옵션을 확인하고 있습니다. 결제 버튼은 누르지 않습니다.")
            continuation.invokeOnCancellation {
                handler.post {
                    if (current === session) finish(AutoBuyOutcome.Blocked("결제 전 확인을 중지했습니다. 결제하지 않았습니다."))
                }
            }
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.nativeUri))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(CartAssistController.COUPANG_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                handler.postDelayed(timeout, TIMEOUT_MS)
                handler.postDelayed(poll, 500)
            } catch (_: RuntimeException) {
                finish(AutoBuyOutcome.Blocked("쿠팡 상품 화면을 열지 못했습니다. 결제하지 않았습니다."))
            }
        }
    }

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (current != null) finish(AutoBuyOutcome.Blocked("결제 전 확인을 중지했습니다. 결제하지 않았습니다."))
        else dryRunJob?.cancel()
    }

    internal fun connected(service: CartAssistService) { this.service = service }
    internal fun disconnected(service: CartAssistService) {
        if (this.service === service) { cancel(); this.service = null }
    }

    private val timeout = Runnable {
        val session = current ?: return@Runnable
        finish(AutoBuyOutcome.Blocked(if (session.checkoutSeen) session.lastCheckoutProblem else "상품명·옵션과 주문 화면을 확인하지 못해 멈췄습니다. 결제하지 않았습니다."))
    }
    private val poll = object : Runnable {
        override fun run() {
            if (current == null) return
            service?.inspectCurrentWindow()
            if (current != null) handler.postDelayed(this, 500)
        }
    }

    internal fun inspectWindow(root: AccessibilityNodeInfo?) {
        val session = current ?: return
        if (inspecting || session.actionPending || root == null) return
        inspecting = true
        try {
            val now = SystemClock.elapsedRealtime()
            if (root.packageName?.toString() != CartAssistController.COUPANG_PACKAGE) {
                if (session.coupangSeen && now - session.startedAt > 1_500) cancel()
                return
            }
            session.coupangSeen = true
            val scanned = scan(root)
            val nodes = scanned.map { it.second }
            if (scanned.any { it.first.isPassword } || nodes.any { it.visible && Regex("^(로그인|본인인증|비밀번호 입력|보안문자 입력|인증번호 입력)$").matches(it.text.trim()) }) {
                finish(AutoBuyOutcome.Blocked("로그인 또는 본인 인증은 직접 진행해 주세요. 도우미는 인증 화면을 조작하지 않습니다."))
                return
            }
            if (nodes.any { it.visible && it.resourceId == PREFIX + "text_main_title" && it.text == "주문/결제" }) {
                // A stale checkout from before the public product link must not become evidence.
                if (!session.checkoutRequested) return
                inspectCheckout(session, scanned, now)
                return
            }
            if (session.checkoutRequested || now - session.startedAt < 1_800) return
            val target = CartAssistPolicy.buyNowTargetIndex(nodes, session.profile.expectedTitle, session.profile.expectedOption)
            if (target != null) {
                if (session.identitySeenAt == 0L) { session.identitySeenAt = now; return }
                if (now - session.identitySeenAt < 500) return
                // The claim is durable before the one permitted external mutation: entering checkout.
                session.actionPending = true
                scope.launch {
                    try {
                        val claimed = AutoBuyStore(session.context).markExternalMutationStarted(session.attemptId)
                        if (current !== session) return@launch
                        session.actionPending = false
                        if (!claimed) { finish(AutoBuyOutcome.Blocked("실행 기록을 저장하지 못해 중지했습니다. 결제하지 않았습니다.")); return@launch }
                        session.checkoutRequested = true
                        session.scrolls = 0
                        session.lastScrollAt = 0
                        // Re-read the entire detail identity after the asynchronous durable claim.
                        val fresh = service?.rootInActiveWindow?.let(::scan).orEmpty()
                        val freshTarget = CartAssistPolicy.buyNowTargetIndex(fresh.map { it.second }, session.profile.expectedTitle, session.profile.expectedOption)
                        if (freshTarget == null || !fresh[freshTarget].first.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            finish(AutoBuyOutcome.Blocked("상품 화면이 변경되었거나 바로구매가 동작하지 않아 멈췄습니다. 결제하지 않았습니다."))
                        } else mutableState.value = AutoBuyRuntimeState.Running("주문/결제 화면에서 상품·수량·배송비·최종 금액을 읽고 있습니다. 결제는 차단돼 있습니다.")
                    } catch (_: Exception) {
                        if (current === session) finish(AutoBuyOutcome.Blocked("실행 기록을 확인하지 못해 중지했습니다. 결제하지 않았습니다."))
                    }
                }
                return
            }
            session.identitySeenAt = 0
            if (CartAssistPolicy.isDetailScreen(nodes)) scroll(session, scanned, CartAssistPolicy.SCROLL_IDS, now, 4)
        } catch (_: RuntimeException) {
            finish(AutoBuyOutcome.Blocked("쿠팡 화면을 읽지 못해 중지했습니다. 결제하지 않았습니다."))
        } finally { inspecting = false }
    }

    private fun inspectCheckout(session: Session, scanned: List<Pair<AccessibilityNodeInfo, CartAssistPolicy.Node>>, now: Long) {
        session.checkoutSeen = true
        val nodes = scanned.map { it.second }
        val hasPayment = nodes.any { it.visible && it.resourceId == PREFIX + "purchase_btn" && it.text == "결제하기" }
        // The inspected total row can contain both an old and a discounted price. Both remain
        // candidates: accessibility text does not identify strikethrough styling reliably.
        nodes.withIndex().filter { it.value.visible && CartAssistPolicy.normalize(it.value.text) == "총결제금액" }.forEach { (index, _) ->
            val grandparent = nodes.getOrNull(nodes[index].parentIndex ?: -1)?.parentIndex
            if (grandparent != null) descendants(nodes, grandparent).mapNotNull { AutoBuyCheckoutPolicy.parseWon(it.text) }.forEach { session.totals += it }
        }
        if (session.totals.size > 1) {
            session.lastCheckoutProblem = "주문/결제 화면에서 최종 금액 후보가 ${session.totals.joinToString(" / ") { "%,d원".format(it) }}으로 함께 표시돼 정확한 금액을 구분하지 못했습니다. 결제하지 않았습니다."
        }
        // Product-row and quantity selectors have not been verified against this Coupang version.
        // An observed price alone must never be upgraded into a verified checkout or authorization.
        val facts = AutoBuyCheckoutPolicy.CheckoutFacts(emptyList(), session.totals.toList(), null, true, hasPayment)
        when (val result = AutoBuyCheckoutPolicy.inspect(session.profile, facts)) {
            is AutoBuyCheckoutPolicy.Check.Ready -> {
                verifiedPreviewRevisions += session.profile.revisionId
                finish(AutoBuyOutcome.DryRunReady(result.totalWon))
                return
            }
            is AutoBuyCheckoutPolicy.Check.Reject -> if (session.totals.size <= 1) session.lastCheckoutProblem = result.reason + " 결제하지 않았습니다."
        }
        if (!scroll(session, scanned, setOf(PREFIX + "checkout_list_area"), now, 6) && session.scrolls >= 6) {
            finish(AutoBuyOutcome.Blocked(session.lastCheckoutProblem))
        }
    }

    private fun scroll(session: Session, scanned: List<Pair<AccessibilityNodeInfo, CartAssistPolicy.Node>>, ids: Set<String>, now: Long, maximum: Int): Boolean {
        if (session.scrolls >= maximum || now - session.lastScrollAt < 1_000) return false
        val node = scanned.firstOrNull { it.second.visible && it.second.enabled && it.second.resourceId in ids && it.first.isScrollable }?.first ?: return false
        session.scrolls += 1
        session.lastScrollAt = now
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun descendants(nodes: List<CartAssistPolicy.Node>, parent: Int): List<CartAssistPolicy.Node> = nodes.withIndex().filter { (index, _) ->
        var ancestor: Int? = index
        var depth = 0
        while (ancestor != null && ancestor != parent && depth++ < 40) ancestor = nodes.getOrNull(ancestor)?.parentIndex
        ancestor == parent
    }.map { it.value }

    private fun scan(root: AccessibilityNodeInfo): List<Pair<AccessibilityNodeInfo, CartAssistPolicy.Node>> {
        val result = mutableListOf<Pair<AccessibilityNodeInfo, CartAssistPolicy.Node>>()
        fun visit(node: AccessibilityNodeInfo, parent: Int?, ancestors: List<String>, depth: Int) {
            if (result.size >= 1_000 || depth > 40 || node.packageName?.toString() != CartAssistController.COUPANG_PACKAGE) return
            val index = result.size
            val id = node.viewIdResourceName.orEmpty()
            result += node to CartAssistPolicy.Node(
                text = if (node.isPassword) "" else node.text?.toString().orEmpty(), resourceId = id, parentIndex = parent,
                ancestorIds = ancestors, visible = node.isVisibleToUser, enabled = node.isEnabled,
                clickable = node.isClickable, childCount = node.childCount,
            )
            for (child in 0 until node.childCount) node.getChild(child)?.let { visit(it, index, if (id.isEmpty()) ancestors else ancestors + id, depth + 1) }
        }
        visit(root, null, emptyList(), 0)
        return result
    }

    private fun finish(outcome: AutoBuyOutcome) {
        val session = current ?: return
        current = null
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(poll)
        mutableState.value = AutoBuyRuntimeState.Finished(outcome)
        if (session.continuation.isActive) session.continuation.resume(outcome)
    }
}
