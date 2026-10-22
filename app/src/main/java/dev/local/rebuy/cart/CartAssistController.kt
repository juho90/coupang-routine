package dev.local.rebuy.cart

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.UUID
import dev.local.rebuy.autobuy.AutoBuyController

data class CartAssistRequest(
    val nativeUri: String,
    val expectedTitle: String,
    val expectedOption: String,
)

sealed interface CartAssistState {
    data object Idle : CartAssistState
    data class Running(val message: String) : CartAssistState
    data class Finished(val message: String, val success: Boolean) : CartAssistState
}

/** One explicit foreground request only. Sessions are never restored or scheduled in the background. */
object CartAssistController {
    private const val PREFS = "cart_assist_attempt"
    private const val TIMEOUT_MS = 45_000L
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow<CartAssistState>(CartAssistState.Idle)
    val state: StateFlow<CartAssistState> = mutableState.asStateFlow()

    private var service: CartAssistService? = null
    private var session: Session? = null
    private var appContext: Context? = null

    internal data class Session(
        val id: String,
        val request: CartAssistRequest,
        val startedAt: Long = SystemClock.elapsedRealtime(),
        var coupangSeen: Boolean = false,
        var clickClaimed: Boolean = false,
        var scrollCount: Int = 0,
        var lastScrollAt: Long = 0,
        var identitySeenAt: Long = 0,
    )

    private val timeout = Runnable {
        val current = session ?: return@Runnable
        finish(
            if (current.clickClaimed) "결과 확인 필요: 쿠팡 장바구니를 직접 확인해 주세요. 자동으로 다시 누르지 않습니다."
            else "상품명·옵션과 담기 버튼을 확인하지 못해 멈췄습니다. 쿠팡에서 직접 확인해 주세요.",
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

    /** Must be called from an explicit UI confirmation; this always means one additional cart unit. */
    fun start(context: Context, request: CartAssistRequest): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (session != null || AutoBuyController.isActive()) return false
        appContext = context.applicationContext
        if (!validRequest(request)) return reject("상품 주소와 화면에 표시되는 상품명·옵션을 확인해 주세요.")
        if (!isEnabled(context) || service == null) {
            return reject("접근성 설정에서 다시담기 장바구니 도우미를 직접 켠 뒤 다시 시도해 주세요.")
        }
        val current = Session(UUID.randomUUID().toString(), request)
        if (!persist(current.id, "armed")) return reject("중복 처리 방지 기록을 저장하지 못해 시작하지 않았습니다.")
        session = current
        mutableState.value = CartAssistState.Running("쿠팡에서 상품명·옵션과 장바구니 담기 버튼을 확인하고 있습니다.")
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(request.nativeUri))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(COUPANG_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            handler.postDelayed(timeout, TIMEOUT_MS)
            handler.postDelayed(inspect, 500)
            true
        } catch (_: RuntimeException) {
            finish("쿠팡 앱의 상품 화면을 열지 못했습니다.", false)
            false
        }
    }

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val current = session ?: return
        finish(
            if (current.clickClaimed) "중지했습니다. 이미 담기 버튼을 눌렀으므로 장바구니에서 결과를 확인해 주세요."
            else "장바구니 도우미를 중지했습니다.",
            false,
        )
    }

    internal fun connected(service: CartAssistService) {
        this.service = service
        appContext = service.applicationContext
        // A process restart never resumes an old request, especially one already sent to Coupang.
        val previous = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("status", null)
        if (session == null && previous in setOf("armed", "click_claimed")) {
            mutableState.value = CartAssistState.Finished(
                "이전 실행이 중단됐습니다. 쿠팡 장바구니에서 결과를 확인해 주세요. 자동으로 다시 실행하지 않습니다.",
                false,
            )
        }
    }

    internal fun disconnected(service: CartAssistService) {
        if (this.service !== service) return
        if (session != null) cancel()
        this.service = null
    }

    internal fun currentSession(): Session? = session

    internal fun claimClick(current: Session): Boolean {
        if (session !== current || current.clickClaimed) return false
        // Claim in memory before disk or the external effect; event re-entry cannot click twice.
        current.clickClaimed = true
        if (!persist(current.id, "click_claimed")) {
            finish("중복 처리 방지 기록을 저장하지 못해 담기 버튼을 누르지 않았습니다.", false)
            return false
        }
        mutableState.value = CartAssistState.Running("담기 버튼을 한 번 눌렀습니다. 쿠팡의 완료 안내를 확인하고 있습니다.")
        return true
    }

    internal fun finish(message: String, success: Boolean) {
        val current = session ?: return
        session = null
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(inspect)
        persist(current.id, if (success) "confirmed" else "stopped")
        mutableState.value = CartAssistState.Finished(message, success)
    }

    private fun reject(message: String): Boolean {
        mutableState.value = CartAssistState.Finished(message, false)
        return false
    }

    private fun persist(id: String, status: String): Boolean =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("attempt_id", id)?.putString("status", status)?.commit() == true

    internal fun validRequest(request: CartAssistRequest): Boolean = runCatching {
        val uri = URI(request.nativeUri)
        val params = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
        val expectedKeys = setOf("pId", "itemId", "vendorItemId")
        uri.scheme == "coupang" && uri.host == "product" && uri.rawAuthority == "product" &&
            uri.rawPath.orEmpty().isEmpty() && uri.rawFragment == null &&
            params.size == 3 && params.map { it.first() }.toSet() == expectedKeys &&
            params.all { it.size == 2 && it[1].matches(Regex("[1-9][0-9]{0,24}")) } &&
            CartAssistPolicy.normalize(request.expectedTitle).length >= 4 &&
            CartAssistPolicy.normalize(request.expectedOption).length >= 2 &&
            request.expectedTitle.length <= 250 && request.expectedOption.length <= 120
    }.getOrDefault(false)

    internal const val COUPANG_PACKAGE = "com.coupang.mobile"
}
