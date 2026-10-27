package dev.local.rebuy.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.rebuy.autobuy.AutoBuyController
import dev.local.rebuy.cart.CartAssistController
import dev.local.rebuy.cart.CartAssistPolicy
import dev.local.rebuy.cart.CartAssistState
import dev.local.rebuy.cart.CartBatchController
import dev.local.rebuy.cart.CartBatchEntryStatus
import dev.local.rebuy.cart.CartBatchTarget
import dev.local.rebuy.data.RoutineItem
import dev.local.rebuy.domain.CoupangCartProductLink

/** Resolve every URL while visible; only an explicit start hands a frozen list to the runner. */
@Composable
internal fun CartBatchDialog(items: List<RoutineItem>, onPreparingChanged: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val batch by CartBatchController.state.collectAsStateWithLifecycle()
    val snapshot = remember { items.toList() }
    val targets = remember(snapshot) {
        snapshot.filter { it.enabled && !it.archived }.map {
            CartBatchTarget(it.id, it.title, it.productUrl, it.purchaseOption.orEmpty(), it.quantity)
        }
    }
    val pausedCount = snapshot.count { !it.archived && !it.enabled }
    val archivedCount = snapshot.count { it.archived }
    val invalid = targets.filter {
        it.quantity != 1 || CartAssistPolicy.normalize(it.title).length < 4 ||
            it.title.length > 250 || CartAssistPolicy.normalizeOption(it.option).length < 2 ||
            it.option.length > 120 || it.productUrl.isBlank()
    }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var hasStarted by rememberSaveable { mutableStateOf(batch.running) }
    var preflightWasPending by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<CartBatchTarget>?>(null) }
    var resolved by remember { mutableStateOf<List<CartBatchTarget>>(emptyList()) }
    var resolvingIndex by remember { mutableIntStateOf(0) }
    var sequence by remember { mutableLongStateOf(0) }
    val preparing = pending != null
    val preparingCallback by rememberUpdatedState(onPreparingChanged)

    fun stopPreparation(message: String? = null) {
        pending = null
        preflightWasPending = false
        preparingCallback(false)
        if (message != null) error = message
    }
    fun dismiss() {
        stopPreparation()
        if (batch.running) CartBatchController.cancel()
        onDismiss()
    }
    LaunchedEffect(Unit) {
        if (preflightWasPending && pending == null) {
            stopPreparation("화면이 바뀌어 상품 주소 확인을 중단했습니다. 목록을 확인한 뒤 다시 시작해 주세요.")
        }
    }
    LaunchedEffect(preparing) { preparingCallback(preparing) }
    DisposableEffect(Unit) { onDispose { preparingCallback(false) } }
    LaunchedEffect(sequence, resolvingIndex, preparing) {
        val queue = pending ?: return@LaunchedEffect
        if (resolvingIndex == queue.size) {
            val ready = resolved.toList()
            stopPreparation()
            hasStarted = true
            if (!CartBatchController.start(context, ready)) {
                hasStarted = false
                error = CartBatchController.state.value.message
            }
            return@LaunchedEffect
        }
        val target = queue[resolvingIndex]
        val product = runCatching { CoupangCartProductLink.parse(target.productUrl) }.getOrNull()
        if (product != null) {
            resolved = resolved + target.copy(productUrl = product.canonicalUrl)
            resolvingIndex += 1
        }
    }
    fun begin() {
        if (preparing || batch.running) return
        error = when {
            targets.isEmpty() -> "담을 상품이 없습니다. 상품을 등록하고 일시정지를 해제해 주세요."
            targets.size > 20 -> "한 번에 최대 20개 상품을 담을 수 있습니다."
            invalid.isNotEmpty() -> "표시된 상품의 구매 수량을 1개로 맞추고 상품명·옵션·URL을 저장해 주세요."
            CartAssistController.state.value is CartAssistState.Running || AutoBuyController.isActive() ->
                "다른 구매 도우미가 실행 중입니다. 실행을 마친 뒤 다시 시작해 주세요."
            !CartAssistController.isEnabled(context) -> "Android 접근성 설정에서 다시담기 구매 도우미를 켜 주세요."
            else -> null
        }
        if (error != null) return
        hasStarted = false
        resolved = emptyList()
        resolvingIndex = 0
        sequence += 1
        preflightWasPending = true
        pending = targets.toList()
        preparingCallback(true)
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("등록 상품 모두 담기") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("상품별 담기 버튼을 한 번씩 누릅니다. 기존 장바구니 수량은 늘어날 수 있으며 실제 수량은 쿠팡에서 확인해 주세요.")
                Text("모든 상품 주소를 먼저 확인한 뒤 순서대로 진행합니다. 한 상품이 실패하면 나머지는 실행하지 않습니다. 주문·결제·구매 완료 기록은 하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                if (pausedCount > 0 || archivedCount > 0) Text("대상에서 제외: 일시정지 ${pausedCount}개 · 보관 ${archivedCount}개", style = MaterialTheme.typography.bodySmall)
                if (preparing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("상품 주소 확인 ${resolvingIndex + 1} / ${pending?.size ?: 0}")
                    val target = pending?.getOrNull(resolvingIndex)
                    if (target != null && runCatching { CoupangCartProductLink.parse(target.productUrl) }.isFailure) {
                        val activeSequence = sequence
                        val activeIndex = resolvingIndex
                        key(activeSequence, activeIndex) {
                            CartShareResolutionPanel(
                                CartShareResolution(activeSequence * 100 + activeIndex, target.productUrl, target.title, target.option, true),
                                onResolved = { canonical ->
                                    if (pending != null && sequence == activeSequence && resolvingIndex == activeIndex) {
                                        resolved = resolved + target.copy(productUrl = canonical)
                                        resolvingIndex += 1
                                    }
                                },
                                onFailure = { message ->
                                    if (pending != null && sequence == activeSequence && resolvingIndex == activeIndex) stopPreparation(message)
                                },
                            )
                        }
                    }
                }
                if (batch.running || hasStarted) {
                    if (batch.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("담기 완료 ${batch.addedCount} / ${batch.totalCount}", fontWeight = FontWeight.SemiBold)
                    if (batch.message.isNotBlank()) Text(batch.message)
                    batch.entries.forEach { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(entry.target.title, fontWeight = FontWeight.SemiBold)
                            Text(when (entry.status) {
                                CartBatchEntryStatus.PENDING -> "대기"
                                CartBatchEntryStatus.RUNNING -> "확인·담기 진행 중"
                                CartBatchEntryStatus.ADDED -> "담기 완료 안내 확인"
                                CartBatchEntryStatus.FAILED -> "실패 · ${entry.message}"
                                CartBatchEntryStatus.NOT_RUN -> "실행하지 않음 · ${entry.message}"
                            }, style = MaterialTheme.typography.bodySmall,
                                color = if (entry.status == CartBatchEntryStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Text("진행 대상 ${targets.size}개", fontWeight = FontWeight.SemiBold)
                    targets.forEach { target ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(target.title, fontWeight = FontWeight.SemiBold)
                            Text("옵션 ${target.option.ifBlank { "미입력" }} · 구매 수량 ${target.quantity}개", style = MaterialTheme.typography.bodySmall)
                            if (target in invalid) Text("등록 정보를 수정해야 합니다.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (batch.entries.isNotEmpty()) Text("지난 실행: ${batch.addedCount}/${batch.totalCount}개 완료 · ${batch.message}", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!CartAssistController.isEnabled(context) && !batch.running && !preparing) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            .onFailure { error = "Android 설정에서 접근성 메뉴를 직접 열어 주세요." }
                    }) { Text("접근성 설정 열기") }
                }
            }
        },
        confirmButton = {
            when {
                batch.running -> TextButton(onClick = { CartBatchController.cancel() }, modifier = Modifier.testTag("cart-batch-cancel")) { Text("실행 중단") }
                preparing -> TextButton(onClick = { stopPreparation("상품 주소 확인을 중단했습니다.") }) { Text("주소 확인 중단") }
                !hasStarted -> Button(onClick = ::begin, enabled = targets.isNotEmpty() && targets.size <= 20 && invalid.isEmpty(), modifier = Modifier.testTag("cart-batch-start")) { Text("${targets.size}개 상품 담기 시작") }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text(if (batch.running) "중단하고 닫기" else "닫기") } },
    )
}
