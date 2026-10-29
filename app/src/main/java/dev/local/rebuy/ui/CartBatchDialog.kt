package dev.local.rebuy.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import dev.local.rebuy.cart.CartBatchScope
import dev.local.rebuy.cart.CartBatchSelection
import dev.local.rebuy.cart.CartBatchValidation
import dev.local.rebuy.data.RoutineItem
import dev.local.rebuy.domain.CoupangCartProductLink
import java.time.LocalDate
import java.text.NumberFormat
import java.util.Locale

/** Resolve every URL while visible; only an explicit start hands a frozen list to the runner. */
@Composable
internal fun CartBatchDialog(items: List<RoutineItem>, today: Long, onPreparingChanged: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val batch by CartBatchController.state.collectAsStateWithLifecycle()
    var scope by rememberSaveable { mutableStateOf(CartBatchScope.DUE) }
    val targets = remember(items, today, scope) {
        CartBatchSelection.select(items, today, scope).map {
            CartBatchTarget(it.id, it.title, it.productUrl, it.purchaseOption.orEmpty(), it.quantity, it.maxTotalWon)
        }
    }
    val dueCount = CartBatchSelection.select(items, today, CartBatchScope.DUE).size
    val activeCount = CartBatchSelection.select(items, today, CartBatchScope.ALL_ACTIVE).size
    val inactiveCount = items.count { !it.archived && !it.enabled }
    val invalid = targets.filter {
        it.quantity != 1 || CartAssistPolicy.normalize(it.title).length < 4 ||
            it.title.length > 250 || CartAssistPolicy.normalizeOption(it.option).length < 2 ||
            it.option.length > 120 || it.productUrl.isBlank()
    }
    val linkProblems = remember(targets) {
        targets.mapNotNull { target -> CartBatchValidation.directLinkProblem(target)?.let { target.itemId to it } }.toMap()
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
    LaunchedEffect(items, today, scope) {
        if (pending != null) stopPreparation("구매 일정이나 등록 정보가 바뀌어 확인을 중단했습니다. 대상 목록을 확인하고 다시 시작해 주세요.")
    }
    LaunchedEffect(preparing) { preparingCallback(preparing) }
    DisposableEffect(Unit) { onDispose { preparingCallback(false) } }
    LaunchedEffect(sequence, resolvingIndex, preparing) {
        val queue = pending ?: return@LaunchedEffect
        if (resolvingIndex == queue.size) {
            if (LocalDate.now().toEpochDay() != today || queue != targets) {
                stopPreparation("날짜나 대상 목록이 바뀌었습니다. 현재 구매 예정 상품을 확인하고 다시 시작해 주세요.")
                return@LaunchedEffect
            }
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
            val problem = CartBatchValidation.directLinkProblem(target)
            if (problem != null) stopPreparation(problem)
            else {
                resolved = resolved + target.copy(productUrl = product.canonicalUrl)
                resolvingIndex += 1
            }
        }
    }
    fun begin() {
        if (preparing || batch.running) return
        error = when {
            LocalDate.now().toEpochDay() != today -> "날짜가 바뀌었습니다. 현재 구매 예정 상품을 확인하고 다시 시작해 주세요."
            targets.isEmpty() -> if (scope == CartBatchScope.DUE) "오늘까지 구매 예정인 상품이 없습니다. 전체 활성 상품을 보려면 대상 범위를 바꿔 주세요." else "담을 활성 상품이 없습니다. 상품을 등록하거나 카드의 스위치로 활성화해 주세요."
            targets.size > 20 -> "한 번에 최대 20개 상품을 담을 수 있습니다."
            invalid.isNotEmpty() -> "상품 수정에서 구매 수량을 1개로 맞추고 링크로 상품 정보를 불러와 주세요."
            linkProblems.isNotEmpty() -> linkProblems.values.joinToString("\n") + "\n이번 묶음 담기는 시작하지 않았습니다."
            CartAssistController.state.value is CartAssistState.Running || AutoBuyController.isActive() ->
                "다른 구매 도우미가 실행 중입니다. 실행을 마친 뒤 다시 시작해 주세요."
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
        title = { Text(if (scope == CartBatchScope.DUE) "구매 예정 상품 담기" else "전체 활성 상품 담기") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!batch.running && !hasStarted) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = scope == CartBatchScope.DUE,
                            onClick = { scope = CartBatchScope.DUE; error = null },
                            enabled = !preparing,
                            label = { Text("구매 예정 $dueCount") },
                            modifier = Modifier.testTag("cart-batch-scope-due"),
                        )
                        FilterChip(
                            selected = scope == CartBatchScope.ALL_ACTIVE,
                            onClick = { scope = CartBatchScope.ALL_ACTIVE; error = null },
                            enabled = !preparing,
                            label = { Text("전체 활성 $activeCount") },
                            modifier = Modifier.testTag("cart-batch-scope-all"),
                        )
                    }
                }
                Text(if (scope == CartBatchScope.DUE) "다음 구매일이 오늘이거나 지난 활성 상품만 선택한 옵션으로 담습니다." else "다음 구매일과 관계없이 활성 상품 전체를 선택한 옵션으로 담습니다.")
                if (scope == CartBatchScope.DUE && !batch.running && !hasStarted && targets.isEmpty()) {
                    Text("오늘까지 구매 예정인 상품이 없습니다. 미리 담으려면 ‘전체 활성’을 선택해 주세요.", style = MaterialTheme.typography.bodySmall)
                }
                Text("한 번에 최대 20개 상품을 각각 1개씩 담습니다. 담기 완료 기록이 있는 상품은 건너뛰며, 이전 요청 결과가 불확실한 상품은 확인 필요로 표시하고 재추가하지 않습니다. 직접 담은 상품은 알 수 없으므로 실제 장바구니도 확인해 주세요.", style = MaterialTheme.typography.bodySmall)
                Text("한도가 있는 상품은 담기 전 화면의 상품가를 비교합니다. 판매 불가 상품은 실패로 표시하고 다음 상품을 확인합니다. 한도 초과·가격 불명확·그 밖의 담기 실패 시에는 나머지를 멈춥니다. 배송비·최종 결제 금액은 별도이며 주문·결제·구매 완료 기록은 실행하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                if (inactiveCount > 0) Text("대상에서 제외: 비활성 ${inactiveCount}개", style = MaterialTheme.typography.bodySmall)
                if (preparing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("상품 링크 확인 ${minOf(resolvingIndex + 1, pending?.size ?: 0)} / ${pending?.size ?: 0}")
                    val target = pending?.getOrNull(resolvingIndex)
                    if (target != null && runCatching { CoupangCartProductLink.parse(target.productUrl) }.isFailure) {
                        val activeSequence = sequence
                        val activeIndex = resolvingIndex
                        key(activeSequence, activeIndex) {
                            CartShareResolutionPanel(
                                CartShareResolution(activeSequence * 100 + activeIndex, target.productUrl, target.title, target.option),
                                onResolved = { canonical ->
                                    if (pending != null && sequence == activeSequence && resolvingIndex == activeIndex) {
                                        val resolvedTarget = target.copy(productUrl = canonical)
                                        val problem = CartBatchValidation.directLinkProblem(resolvedTarget)
                                        if (problem != null) stopPreparation(problem)
                                        else {
                                            resolved = resolved + resolvedTarget
                                            resolvingIndex += 1
                                        }
                                    }
                                },
                                onFailure = { message ->
                                    if (pending != null && sequence == activeSequence && resolvingIndex == activeIndex) stopPreparation(CartBatchValidation.namedLinkProblem(target, message))
                                },
                            )
                        }
                    }
                }
                if (batch.running || hasStarted) {
                    if (batch.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("새로 담음 ${batch.addedCount}개 · 대상 ${batch.totalCount}개", fontWeight = FontWeight.SemiBold)
                    Text("기록 있어 건너뜀 ${batch.skippedCount}개 · 확인 필요 ${batch.reviewCount}개 · 실패 ${batch.failedCount}개", style = MaterialTheme.typography.bodySmall)
                    if (batch.message.isNotBlank()) Text(batch.message)
                    batch.entries.forEach { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(entry.target.title, fontWeight = FontWeight.SemiBold)
                            Text(when (entry.status) {
                                CartBatchEntryStatus.PENDING -> "대기"
                                CartBatchEntryStatus.RUNNING -> "확인·담기 진행 중"
                                CartBatchEntryStatus.ADDED -> "담기 완료 안내 확인"
                                CartBatchEntryStatus.SKIPPED -> "기록 있어 건너뜀 · ${entry.message}"
                                CartBatchEntryStatus.REVIEW_REQUIRED -> "확인 필요 · ${entry.message}"
                                CartBatchEntryStatus.FAILED -> "실패 · ${entry.message}"
                                CartBatchEntryStatus.NOT_RUN -> "실행하지 않음 · ${entry.message}"
                            }, style = MaterialTheme.typography.bodySmall,
                                color = if (entry.status in setOf(CartBatchEntryStatus.FAILED, CartBatchEntryStatus.REVIEW_REQUIRED)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Text("담을 상품 ${targets.size}개", fontWeight = FontWeight.SemiBold)
                    targets.forEach { target ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(target.title, fontWeight = FontWeight.SemiBold)
                            Text("선택 옵션 ${target.option.ifBlank { "확인 필요" }} · 구매 수량 ${target.quantity}개", style = MaterialTheme.typography.bodySmall)
                            target.maxTotalWon?.let { maximum ->
                                Text("등록 한도 ${NumberFormat.getIntegerInstance(Locale.KOREA).format(maximum)}원", style = MaterialTheme.typography.bodySmall)
                            }
                            if (target in invalid) Text("등록 정보를 수정해야 합니다.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            linkProblems[target.itemId]?.let { problem ->
                                Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("cart-batch-link-error-${target.itemId}"))
                            }
                        }
                    }
                    if (batch.entries.isNotEmpty()) Text("지난 실행: 새로 담음 ${batch.addedCount}개 · 기록 있어 건너뜀 ${batch.skippedCount}개 · 확인 필요 ${batch.reviewCount}개 · 실패 ${batch.failedCount}개 · ${batch.message}", style = MaterialTheme.typography.bodySmall)
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
                !hasStarted -> Button(onClick = ::begin, enabled = targets.isNotEmpty() && targets.size <= 20 && invalid.isEmpty(), modifier = Modifier.testTag("cart-batch-start")) { Text("${targets.size}개 확인 후 담기") }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text(if (batch.running) "중단하고 닫기" else "닫기") } },
    )
}
