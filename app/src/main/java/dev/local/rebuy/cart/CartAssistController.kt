package dev.local.rebuy.cart

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import dev.local.rebuy.ProductLauncher
import dev.local.rebuy.autobuy.AutoBuyController
import dev.local.rebuy.domain.CoupangCartProductLink
import dev.local.rebuy.importproduct.ProductLinkImportController

data class CartAssistRequest(
    val productUrl: String,
    val expectedTitle: String,
    val expectedOption: String,
    val verifyOnly: Boolean = false,
    val maxTotalWon: Long? = null,
)

enum class CartAssistStage {
    IDLE, OPENING_PRODUCT, WAITING_NATIVE_APP, NO_ACTIVE_WINDOW, OUTSIDE_COUPANG,
    WAITING_DETAIL_SCREEN, WAITING_TITLE, WAITING_OPTION, WAITING_CART_BUTTON,
    IDENTITY_STABILIZING, SCROLLING_DETAIL, CHECKING_PRICE, VERIFIED, CLICK_CLAIMED,
    WAITING_CONFIRMATION, CONFIRMED, CANCELLED, TIMED_OUT, FAILED,
}

enum class CartAssistStopReason {
    NONE, USER_CANCEL, ACCESSIBILITY_INTERRUPTED, SERVICE_DISCONNECTED, TIMEOUT, OTHER,
}

/** Safe diagnostics only: never native screen text, addresses, account data, or product identifiers. */
data class CartAssistDiagnostics(
    val stage: CartAssistStage = CartAssistStage.IDLE,
    val verifyOnly: Boolean = false,
    val elapsedMillis: Long = 0,
    val scrollCount: Int = 0,
    val titleMatched: Boolean = false,
    val optionMatched: Boolean = false,
    val buttonMatched: Boolean = false,
    val lastActiveStage: CartAssistStage? = null,
    val stopReason: CartAssistStopReason = CartAssistStopReason.NONE,
    val detailStructure: CartDetailStructure? = null,
    val scannedNodeCount: Int = 0,
    val resourceIdNodeCount: Int = 0,
    val scanTruncated: Boolean = false,
    val clickClaimed: Boolean = false,
    val clickAccepted: Boolean? = null,
    val hasClickRecord: Boolean = true,
) {
    internal fun atStage(next: CartAssistStage, reason: CartAssistStopReason = stopReason): CartAssistDiagnostics = copy(
        stage = next,
        lastActiveStage = if (next in terminalStages) lastActiveStage else next,
        stopReason = reason,
    )

    private companion object {
        val terminalStages = setOf(CartAssistStage.VERIFIED, CartAssistStage.CONFIRMED,
            CartAssistStage.CANCELLED, CartAssistStage.TIMED_OUT, CartAssistStage.FAILED)
    }
}

sealed interface CartAssistState {
    data object Idle : CartAssistState
    data class Running(val message: String) : CartAssistState
    data class Finished(val message: String, val success: Boolean, val verifiedOnly: Boolean = false) : CartAssistState
}

/** One explicit foreground request only. Sessions are never restored or scheduled in the background. */
object CartAssistController {
    private const val PREFS = "cart_assist_attempt"
    private const val TIMEOUT_MS = 45_000L
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow<CartAssistState>(CartAssistState.Idle)
    val state: StateFlow<CartAssistState> = mutableState.asStateFlow()
    private val mutableDiagnostics = MutableStateFlow(CartAssistDiagnostics())
    val diagnostics: StateFlow<CartAssistDiagnostics> = mutableDiagnostics.asStateFlow()

    private var service: CartAssistService? = null
    private var session: Session? = null
    private var appContext: Context? = null

    internal data class Session(
        val id: String,
        val request: CartAssistRequest,
        val startedAt: Long = SystemClock.elapsedRealtime(),
        var coupangSeen: Boolean = false,
        var clickClaimed: Boolean = false,
        var clickAccepted: Boolean? = null,
        var scrollCount: Int = 0,
        var lastScrollAt: Long = 0,
        var identitySeenAt: Long = 0,
    ) {
        var priceCheckPassed = request.maxTotalWon == null
        var checkedPriceWon: Long? = null
        private var actionStarted = false
        /** Claims before any external effect, and refuses read-only requests before persistence. */
        fun claimBeforeEffect(persistClaim: () -> Boolean): Boolean {
            if (request.verifyOnly || clickClaimed || !priceCheckPassed) return false
            clickClaimed = true
            return persistClaim()
        }

        /** Independent last gate at the service action boundary, including a mistaken claim. */
        fun performClaimedAction(action: () -> Boolean): Boolean {
            if (request.verifyOnly || !clickClaimed || actionStarted || !priceCheckPassed) return false
            actionStarted = true
            return action().also { clickAccepted = it }
        }

        fun resultStatus(success: Boolean): String = when {
            !success -> "stopped"
            request.verifyOnly -> "verified"
            else -> "confirmed"
        }
    }

    private val timeout = Runnable {
        val current = session ?: return@Runnable
        val lastStage = diagnostics.value.stage
        val stageMessage = when (lastStage) {
            CartAssistStage.OPENING_PRODUCT, CartAssistStage.WAITING_NATIVE_APP -> "쿠팡 네이티브 앱 화면을 확인하지 못했습니다. 브라우저만 열렸는지 확인해 주세요."
            CartAssistStage.NO_ACTIVE_WINDOW -> "접근성에서 현재 화면을 읽지 못했습니다."
            CartAssistStage.WAITING_DETAIL_SCREEN -> "쿠팡 상품 상세 화면의 구조를 확인하지 못했습니다."
            CartAssistStage.WAITING_TITLE -> "상품 상세 화면에서 등록한 상품명을 확인하지 못했습니다."
            CartAssistStage.WAITING_OPTION -> "상품명은 확인했지만 등록한 옵션을 확인하지 못했습니다."
            CartAssistStage.WAITING_CART_BUTTON -> "상품명과 옵션은 확인했지만 담기 버튼의 구조를 확인하지 못했습니다."
            else -> "상품명·옵션과 담기 버튼을 확인하지 못했습니다."
        }
        reportStage(current, CartAssistStage.TIMED_OUT, stopReason = CartAssistStopReason.TIMEOUT)
        finish(
            if (current.clickClaimed) "결과 확인 필요: 쿠팡 장바구니를 직접 확인해 주세요. 자동으로 다시 누르지 않습니다."
            else "$stageMessage 담기 버튼은 누르지 않았습니다.",
            false,
        )
    }

    private val inspect = object : Runnable {
        override fun run() {
            if (session == null) return
            service?.inspectCurrentWindow()
            if (session != null) handler.postDelayed(this, 500)
        }
    }

    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val wanted = ComponentName(context, CartAssistService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val info = it.resolveInfo.serviceInfo
            ComponentName(info.packageName, info.name) == wanted
        }
    }

    /** Explicit UI action only. verifyOnly checks the same screen without requesting cart addition. */
    fun start(context: Context, request: CartAssistRequest): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (session != null || AutoBuyController.isActive() || ProductLinkImportController.isActive()) return false
        appContext = context.applicationContext
        if (!validRequest(request)) return reject("상품 링크와 상품명·선택 규격·금액 한도를 확인해 주세요.")
        if (!request.verifyOnly) {
            val previous = try { CartAdditionLedger(context).lookup(request.productUrl) }
            catch (_: Exception) { return reject("이전 담기 기록을 읽지 못해 중복 추가를 막았습니다. 기록을 확인해 주세요.") }
            if (previous != null) return reject(if (previous.status == CartAdditionLedger.Status.CONFIRMED)
                "이 상품은 앱에서 담기 완료를 확인한 기록이 있습니다. 쿠팡 장바구니를 확인한 뒤 기록을 해제해야 다시 담을 수 있습니다."
                else "이 상품은 이전 담기 요청의 결과 확인이 필요합니다. 쿠팡 장바구니를 확인한 뒤 기록을 해제해 주세요.")
        }
        if (!isEnabled(context)) {
            return reject("접근성 설정에서 다시담기 장바구니 도우미를 직접 켠 뒤 다시 시도해 주세요.")
        }
        if (service == null) return reject("접근성 권한은 켜져 있지만 도우미 연결이 끊겼습니다. 앱 업데이트·강제 종료 뒤 발생할 수 있습니다. 잠시 기다린 뒤에도 계속되면 접근성 설정에서 도우미를 껐다 켜 주세요.")
        val current = Session(UUID.randomUUID().toString(), request)
        mutableDiagnostics.value = CartAssistDiagnostics(CartAssistStage.OPENING_PRODUCT, request.verifyOnly,
            lastActiveStage = CartAssistStage.OPENING_PRODUCT)
        if (!persist(current.id, if (request.verifyOnly) "verifying" else "armed", request.verifyOnly)) return reject("중복 처리 방지 기록을 저장하지 못해 시작하지 않았습니다.")
        session = current
        mutableState.value = CartAssistState.Running(if (request.verifyOnly)
            "담기 전 점검 중입니다. 상품명·옵션·버튼만 확인하며 장바구니에 추가하지 않습니다."
            else "쿠팡에서 상품명·옵션과 장바구니 담기 버튼을 확인하고 있습니다.")
        return try {
            if (!ProductLauncher.openInCoupang(context, request.productUrl, newTask = true)) {
                finish("쿠팡 상품 링크를 열지 못했습니다. 상품 주소와 쿠팡 앱 설치를 확인해 주세요.", false)
                return false
            }
            handler.postDelayed(timeout, TIMEOUT_MS)
            handler.postDelayed(inspect, 500)
            true
        } catch (_: RuntimeException) {
            finish("쿠팡 앱의 상품 화면을 열지 못했습니다.", false)
            false
        }
    }

    fun cancel() = stop(CartAssistStopReason.USER_CANCEL)

    internal fun interrupted() = stop(CartAssistStopReason.ACCESSIBILITY_INTERRUPTED)

    private fun stop(reason: CartAssistStopReason) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val current = session ?: return
        reportStage(current, CartAssistStage.CANCELLED, stopReason = reason)
        finish(
            when {
                current.clickAccepted == true -> "중지했습니다. Android가 담기 버튼 클릭 요청을 수락했으므로 장바구니에서 결과를 확인해 주세요."
                current.clickClaimed -> "중지했습니다. 담기 요청의 결과를 확인하지 못했습니다. 자동으로 다시 누르지 않습니다."
                reason == CartAssistStopReason.ACCESSIBILITY_INTERRUPTED -> "접근성 서비스가 중단되어 멈췄습니다. 담기 버튼은 누르지 않았습니다."
                reason == CartAssistStopReason.SERVICE_DISCONNECTED -> "접근성 서비스 연결이 끊겨 멈췄습니다. 담기 버튼은 누르지 않았습니다."
                else -> "장바구니 도우미를 중지했습니다."
            },
            false,
        )
    }

    internal fun connected(service: CartAssistService) {
        this.service = service
        appContext = service.applicationContext
        restoreDiagnostics(service)
        // A process restart never resumes an old request, especially one already sent to Coupang.
        val previous = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("status", null)
        if (session == null && previous in setOf("armed", "click_claimed", "verifying")) {
            mutableState.value = CartAssistState.Finished(
                if (previous == "verifying") "이전 담기 전 점검이 중단됐습니다. 점검은 자동으로 다시 실행하지 않습니다."
                else "이전 실행이 중단됐습니다. 쿠팡 장바구니에서 결과를 확인해 주세요. 자동으로 다시 실행하지 않습니다.",
                false,
            )
        }
    }

    internal fun disconnected(service: CartAssistService) {
        if (this.service !== service) return
        if (session != null) stop(CartAssistStopReason.SERVICE_DISCONNECTED)
        this.service = null
    }

    internal fun currentSession(): Session? = session

    internal fun claimClick(current: Session): Boolean {
        if (current.request.verifyOnly || session !== current || current.clickClaimed) return false
        // Claim in memory before disk or the external effect; event re-entry cannot click twice.
        if (!current.claimBeforeEffect {
                val context = requireNotNull(appContext)
                CartAdditionLedger(context).claim(current.request.productUrl, current.id) && persist(current.id, "click_claimed", false)
            }) {
            reportStage(current, CartAssistStage.CLICK_CLAIMED)
            finish("중복 처리 방지 기록을 저장하지 못해 담기 버튼을 누르지 않았습니다.", false)
            return false
        }
        reportStage(current, CartAssistStage.CLICK_CLAIMED)
        mutableState.value = CartAssistState.Running("담기 요청을 준비했습니다. 쿠팡 버튼의 응답을 확인하고 있습니다.")
        return true
    }

    internal fun reportClickResult(current: Session) {
        if (session !== current) return
        reportStage(current, if (current.clickAccepted == true) CartAssistStage.WAITING_CONFIRMATION else CartAssistStage.CLICK_CLAIMED)
        if (current.clickAccepted == true) mutableState.value = CartAssistState.Running(
            "Android가 담기 버튼 클릭 요청을 수락했습니다. 담기 완료 안내를 확인하고 있습니다.")
    }

    internal fun reportScan(current: Session, nodeCount: Int, resourceIdCount: Int,
        truncated: Boolean, structure: CartDetailStructure) {
        if (session !== current) return
        updateDiagnostics(mutableDiagnostics.value.copy(scannedNodeCount = nodeCount,
            resourceIdNodeCount = resourceIdCount, scanTruncated = truncated, detailStructure = structure))
    }

    internal fun checkPrice(current: Session, nodes: List<CartAssistPolicy.Node>, truncated: Boolean): Boolean {
        if (session !== current) return false
        reportStage(current, CartAssistStage.CHECKING_PRICE)
        if (truncated && current.request.maxTotalWon != null) {
            finish("상품가 확인에 필요한 화면을 끝까지 읽지 못해 담지 않았습니다.", false)
            return false
        }
        return when (val result = CartPricePolicy.check(nodes, current.request.maxTotalWon)) {
            CartPricePolicy.Result.Unrestricted -> { current.priceCheckPassed = true; true }
            is CartPricePolicy.Result.Allowed -> {
                current.checkedPriceWon = result.priceWon
                current.priceCheckPassed = true
                true
            }
            is CartPricePolicy.Result.Blocked -> { finish(result.message, false); false }
        }
    }

    internal fun clearRecordedAddition(context: Context, url: String): Boolean {
        if (session != null || CartBatchController.isRunning() || AutoBuyController.isActive() || ProductLinkImportController.isActive()) return false
        return CartAdditionLedger(context).clear(url)
    }

    internal fun finishVerified(current: Session) {
        if (session !== current || !current.request.verifyOnly || current.clickClaimed) return
        reportStage(current, CartAssistStage.VERIFIED)
        finish("담기 전 점검을 마쳤습니다. 상품명·옵션·담기 버튼을 확인했으며 장바구니에는 추가하지 않았습니다.", true)
    }

    internal fun reportStage(current: Session, stage: CartAssistStage, inspection: CartAssistPolicy.DetailInspection? = null,
        stopReason: CartAssistStopReason = CartAssistStopReason.NONE) {
        if (session !== current) return
        val previous = mutableDiagnostics.value
        val next = previous.atStage(stage, stopReason).copy(
            verifyOnly = current.request.verifyOnly, elapsedMillis = SystemClock.elapsedRealtime() - current.startedAt,
            scrollCount = current.scrollCount,
            titleMatched = inspection?.titleMatched ?: previous.titleMatched,
            optionMatched = inspection?.optionMatched ?: previous.optionMatched,
            buttonMatched = inspection?.buttonMatched ?: previous.buttonMatched,
            clickClaimed = current.clickClaimed, clickAccepted = current.clickAccepted,
        )
        updateDiagnostics(next)
    }

    internal fun finish(message: String, success: Boolean) {
        val current = session ?: return
        session = null
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(inspect)
        if (!success && diagnostics.value.stage !in setOf(CartAssistStage.CANCELLED, CartAssistStage.TIMED_OUT, CartAssistStage.OUTSIDE_COUPANG)) {
            mutableDiagnostics.value = diagnostics.value.atStage(CartAssistStage.FAILED, CartAssistStopReason.OTHER)
        } else if (!success && diagnostics.value.stopReason == CartAssistStopReason.NONE) {
            mutableDiagnostics.value = diagnostics.value.copy(stopReason = CartAssistStopReason.OTHER)
        }
        val receiptSaved = !success || current.request.verifyOnly || runCatching {
            CartAdditionLedger(requireNotNull(appContext)).confirm(current.request.productUrl, current.id)
        }.getOrDefault(false)
        persist(current.id, current.resultStatus(success), current.request.verifyOnly)
        val priceMessage = current.checkedPriceWon?.let { " 상품가 ${java.text.NumberFormat.getIntegerInstance(java.util.Locale.KOREA).format(it)}원을 등록 한도 안에서 확인했습니다. 배송비·최종 결제 금액은 별도입니다." }.orEmpty()
        mutableState.value = CartAssistState.Finished(message + (if (success) priceMessage else "") +
            (if (receiptSaved) "" else " 완료 기록 저장에 실패해 결과 확인 필요 상태를 유지합니다."), success, verifiedOnly = success && current.request.verifyOnly)
    }

    private fun reject(message: String): Boolean {
        mutableState.value = CartAssistState.Finished(message, false)
        return false
    }

    fun restoreDiagnostics(context: Context) {
        if (session != null) return
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stage = prefs.getString("stage", null)?.let { enumValueOrNull<CartAssistStage>(it) } ?: return
        val structure = if (prefs.getBoolean("structure_present", false)) CartDetailStructure(
            prefs.getBoolean("structure_bottom", false), prefs.getBoolean("structure_buttons", false),
            prefs.getBoolean("structure_content", false), prefs.getBoolean("structure_cart", false),
            prefs.getBoolean("structure_buy", false),
        ) else null
        var restored = CartAssistDiagnostics(
            stage = stage, verifyOnly = prefs.getBoolean("verify_only", false),
            elapsedMillis = prefs.getLong("elapsed_ms", 0).coerceAtLeast(0),
            scrollCount = prefs.getInt("scroll_count", 0).coerceIn(0, 4),
            titleMatched = prefs.getBoolean("title_matched", false), optionMatched = prefs.getBoolean("option_matched", false),
            buttonMatched = prefs.getBoolean("button_matched", false),
            lastActiveStage = prefs.getString("last_active_stage", null)?.let { enumValueOrNull<CartAssistStage>(it) },
            stopReason = prefs.getString("stop_reason", null)?.let { enumValueOrNull<CartAssistStopReason>(it) }
                ?: if (stage == CartAssistStage.TIMED_OUT) CartAssistStopReason.TIMEOUT else CartAssistStopReason.NONE,
            detailStructure = structure, scannedNodeCount = prefs.getInt("scanned_nodes", 0).coerceIn(0, CartNodeTraversal.VISIBLE_SCAN_LIMIT),
            resourceIdNodeCount = prefs.getInt("resource_id_nodes", 0).coerceIn(0, CartNodeTraversal.VISIBLE_SCAN_LIMIT),
            scanTruncated = prefs.getBoolean("scan_truncated", false), clickClaimed = prefs.getBoolean("click_claimed", false),
            clickAccepted = if (prefs.contains("click_accepted")) prefs.getBoolean("click_accepted", false) else null,
            hasClickRecord = prefs.contains("click_claimed"),
        )
        if (prefs.getString("status", null) in setOf("armed", "click_claimed", "verifying")) {
            restored = restored.atStage(CartAssistStage.CANCELLED, CartAssistStopReason.SERVICE_DISCONNECTED)
        }
        mutableDiagnostics.value = restored
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }

    private fun updateDiagnostics(next: CartAssistDiagnostics) {
        val previous = mutableDiagnostics.value
        mutableDiagnostics.value = next
        if (next.copy(elapsedMillis = previous.elapsedMillis) != previous) {
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.also {
                writeDiagnostics(it, next)
            }?.apply()
        }
    }

    private fun writeDiagnostics(editor: SharedPreferences.Editor, snapshot: CartAssistDiagnostics) {
        editor.putString("stage", snapshot.stage.name).putString("last_active_stage", snapshot.lastActiveStage?.name)
            .putString("stop_reason", snapshot.stopReason.name).putLong("elapsed_ms", snapshot.elapsedMillis)
            .putBoolean("verify_only", snapshot.verifyOnly).putBoolean("title_matched", snapshot.titleMatched)
            .putBoolean("option_matched", snapshot.optionMatched).putBoolean("button_matched", snapshot.buttonMatched)
            .putInt("scroll_count", snapshot.scrollCount).putInt("scanned_nodes", snapshot.scannedNodeCount)
            .putInt("resource_id_nodes", snapshot.resourceIdNodeCount).putBoolean("scan_truncated", snapshot.scanTruncated)
            .putBoolean("structure_present", snapshot.detailStructure != null)
        if (snapshot.hasClickRecord) editor.putBoolean("click_claimed", snapshot.clickClaimed) else editor.remove("click_claimed")
        if (snapshot.clickAccepted == null) editor.remove("click_accepted") else editor.putBoolean("click_accepted", snapshot.clickAccepted)
        val structure = snapshot.detailStructure ?: CartDetailStructure()
        editor.putBoolean("structure_bottom", structure.bottomContainer).putBoolean("structure_buttons", structure.buttonContainer)
            .putBoolean("structure_content", structure.detailContent).putBoolean("structure_cart", structure.cartLabel)
            .putBoolean("structure_buy", structure.buyLabel)
    }

    private fun persist(id: String, status: String, verifyOnly: Boolean): Boolean =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("attempt_id", id)?.putString("status", status)?.putBoolean("verify_only", verifyOnly)
            ?.also { writeDiagnostics(it, diagnostics.value.copy(clickClaimed = session?.clickClaimed ?: diagnostics.value.clickClaimed)) }
            ?.commit() == true

    internal fun validRequest(request: CartAssistRequest): Boolean = runCatching {
        if (request.verifyOnly) CoupangCartProductLink.parse(request.productUrl)
        else CoupangCartProductLink.parseForCart(request.productUrl)
        require(request.maxTotalWon == null || request.maxTotalWon in 1L..100_000_000L)
        CartAssistPolicy.normalize(request.expectedTitle).length >= 4 &&
            CartAssistPolicy.normalizeOption(request.expectedOption).length >= 2 &&
            request.expectedTitle.length <= 250 && request.expectedOption.length <= 120
    }.getOrDefault(false)

    internal const val COUPANG_PACKAGE = "com.coupang.mobile"
}
