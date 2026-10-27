package dev.local.rebuy.cart

import android.content.Context
import android.os.Looper
import dev.local.rebuy.autobuy.AutoBuyController
import dev.local.rebuy.importproduct.ProductLinkImportController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** An explicit one-shot queue. Reopening the app never resumes saved work. */
object CartBatchController {
    private const val PREFS = "cart_batch_attempt"
    private const val KEY = "snapshot"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(CartBatchState())
    val state: StateFlow<CartBatchState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var cancelled = false
    private var ownsChild = false
    private var context: Context? = null
    private var restored = false

    fun isRunning(): Boolean = mutableState.value.running

    fun start(context: Context, targets: List<CartBatchTarget>): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (isRunning() || job?.isActive == true) return false
        this.context = context.applicationContext
        restored = true
        val prepared = try { CartBatchValidation.prepare(targets) } catch (error: IllegalArgumentException) {
            mutableState.value = CartBatchState(message = error.message ?: "등록 상품의 구매 조건을 확인해 주세요.")
            return false
        }
        if (AutoBuyController.isActive() || CartAssistController.state.value is CartAssistState.Running || ProductLinkImportController.isActive()) {
            mutableState.value = CartBatchState(message = "다른 구매 도우미가 실행 중입니다. 실행을 마친 뒤 다시 시작해 주세요.")
            return false
        }
        if (prepared.any { !CartAssistController.validRequest(CartAssistRequest(it.productUrl, it.title, it.option)) }) {
            mutableState.value = CartBatchState(message = "상품 주소·상품명·옵션을 확인해 주세요.")
            return false
        }
        cancelled = false
        val initial = CartBatchState(prepared.map(::CartBatchEntry), running = true, message = "여러 상품 담기를 준비하고 있습니다.")
        if (!publish(initial)) {
            mutableState.value = initial.copy(running = false, message = "진행 기록을 저장하지 못해 시작하지 않았습니다.")
            return false
        }
        val nextJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val finished = CartBatchSequence.run(prepared, ::execute, ::publish) { cancelled }
                if (!publish(finished)) mutableState.value = finished.copy(message = finished.message + " 결과 기록 저장은 실패했습니다.")
            } catch (_: CancellationException) {
                publish(CartBatchSequence.interrupted(mutableState.value))
            } catch (_: Exception) {
                publish(CartBatchSequence.interrupted(mutableState.value).copy(
                    message = "여러 상품 담기 중 오류가 발생해 멈췄습니다. 장바구니를 확인해 주세요. 자동으로 다시 담지 않습니다.",
                ))
            } finally {
                if (ownsChild && CartAssistController.state.value is CartAssistState.Running) CartAssistController.cancel()
                ownsChild = false
                job = null
            }
        }
        job = nextJob
        nextJob.start()
        return true
    }

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!isRunning()) return
        cancelled = true
        publish(mutableState.value.copy(message = "중지를 요청했습니다. 현재 상품의 결과를 확인하고 있습니다."))
        if (ownsChild) CartAssistController.cancel()
    }

    fun restore(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (restored || isRunning() || job?.isActive == true) return
        this.context = context.applicationContext
        restored = true
        val snapshot = runCatching {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
            require(raw.length <= 150_000)
            decode(raw)
        }.getOrNull() ?: return
        val restoredState = if (snapshot.running) CartBatchSequence.interrupted(snapshot) else snapshot
        mutableState.value = restoredState
        if (snapshot.running) persist(restoredState)
    }

    private suspend fun execute(target: CartBatchTarget): CartBatchAttemptResult {
        if (cancelled) return CartBatchAttemptResult(false, "사용자가 중지했습니다.")
        return try {
            val started = CartAssistController.start(requireNotNull(context), CartAssistRequest(target.productUrl, target.title, target.option))
            ownsChild = started
            if (!started) {
                CartBatchAttemptResult(false, (CartAssistController.state.value as? CartAssistState.Finished)?.message ?: "상품 도우미를 시작하지 못했습니다.")
            } else {
                val result = withTimeout(50_000) {
                    CartAssistController.state.first { it is CartAssistState.Finished } as CartAssistState.Finished
                }
                CartBatchAttemptResult(result.success, result.message, result.verifiedOnly)
            }
        } catch (_: TimeoutCancellationException) {
            CartBatchAttemptResult(false, "상품의 담기 결과 대기 시간이 초과됐습니다. 장바구니를 확인해 주세요.")
        } finally {
            if (ownsChild && CartAssistController.state.value is CartAssistState.Running) CartAssistController.cancel()
            ownsChild = false
        }
    }

    private fun publish(snapshot: CartBatchState): Boolean {
        mutableState.value = snapshot
        return persist(snapshot)
    }

    private fun persist(snapshot: CartBatchState): Boolean = runCatching {
        requireNotNull(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, encode(snapshot)).commit()
    }.getOrDefault(false)

    private fun encode(snapshot: CartBatchState): String = JSONObject().apply {
        put("version", 1); put("running", snapshot.running); put("message", snapshot.message); put("interrupted", snapshot.interrupted)
        put("entries", JSONArray().apply {
            snapshot.entries.forEach { entry ->
                put(JSONObject().apply {
                    put("itemId", entry.target.itemId); put("title", entry.target.title)
                    put("url", entry.target.productUrl); put("option", entry.target.option)
                    put("quantity", entry.target.quantity); put("status", entry.status.name); put("message", entry.message)
                })
            }
        })
    }.toString()

    private fun decode(raw: String): CartBatchState {
        val json = JSONObject(raw)
        require(json.getInt("version") == 1)
        val entries = json.getJSONArray("entries")
        require(entries.length() <= CartBatchValidation.MAX_TARGETS)
        return CartBatchState(
            entries = (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                CartBatchEntry(
                    CartBatchTarget(entry.getString("itemId"), entry.getString("title"), entry.getString("url"), entry.getString("option"), entry.getInt("quantity")),
                    CartBatchEntryStatus.valueOf(entry.getString("status")), entry.getString("message"),
                )
            },
            running = json.getBoolean("running"), message = json.getString("message"), interrupted = json.getBoolean("interrupted"),
        )
    }
}
