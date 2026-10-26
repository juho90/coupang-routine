package dev.local.rebuy.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.rebuy.autobuy.AutoBuyController
import dev.local.rebuy.autobuy.AutoBuyOutcome
import dev.local.rebuy.autobuy.AutoBuyProfile
import dev.local.rebuy.autobuy.AutoBuyRuntimeState
import dev.local.rebuy.autobuy.AutoBuyStatus
import dev.local.rebuy.autobuy.AutoBuyStore
import dev.local.rebuy.cart.CartAssistController
import dev.local.rebuy.cart.CartAssistPolicy
import dev.local.rebuy.data.RoutineItem
import dev.local.rebuy.settings.ReminderSettings
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AutoBuyDialog(
    items: List<RoutineItem>,
    initialItemId: String?,
    reminderSettings: ReminderSettings,
    onEditItem: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { AutoBuyStore(context.applicationContext) }
    val attempts by store.attempts.collectAsStateWithLifecycle(initialValue = emptyList())
    val runtime by AutoBuyController.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf(initialItemId ?: items.firstOrNull()?.id) }
    val selected = items.firstOrNull { it.id == selectedId }
    var expanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var ownsPreview by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable(selectedId) { mutableStateOf<String?>(null) }
    var serviceEnabled by remember { mutableStateOf(CartAssistController.isEnabled(context)) }
    val running = ownsPreview && runtime is AutoBuyRuntimeState.Running
    val busy = saving || running
    val lastAttempt = attempts.filter { it.itemId == selectedId }.maxByOrNull { it.updatedAtEpochMillis }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) serviceEnabled = CartAssistController.isEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun dismiss() {
        if (running) AutoBuyController.cancel()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("구매 조건 · 결제 전 시험") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("실제 결제 비활성화", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                Text("현재 버전은 결제 직전까지의 시험만 제공합니다. 조건을 저장하거나 시험해도 자동 구매가 켜지지 않으며 결제 버튼을 누르지 않습니다.", style = MaterialTheme.typography.bodySmall)
                Text("쿠팡의 공식 연동이 아닌 화면 조작 실험입니다. 직접 시작한 동안 쿠팡 화면을 읽어 상품과 옵션, 최종 금액을 확인합니다. 화면 정보는 서버에 보내지 않습니다.", style = MaterialTheme.typography.bodySmall)
                if (items.isEmpty()) {
                    Text("먼저 구매 목록에서 상품을 등록해 주세요.")
                } else {
                    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { expanded = true }) {
                        Text(selected?.title ?: "상품 선택")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        items.forEach { item ->
                            DropdownMenuItem(text = { Text(item.title) }, onClick = {
                                selectedId = item.id
                                ownsPreview = false
                                expanded = false
                            })
                        }
                    }
                }
                selected?.let { item ->
                    Text("${item.intervalDays}일마다 · 다음 구매 ${LocalDate.ofEpochDay(item.nextPurchaseEpochDay)} · 알림 시각 ${reminderSettings.hour.toString().padStart(2, '0')}:${reminderSettings.minute.toString().padStart(2, '0')}", style = MaterialTheme.typography.bodySmall)
                    Text("현재 자동 실행 예약은 꺼져 있습니다. 다음 구매일과 주기는 시험으로 변경되지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    if (item.quantity != 1) Text("시험은 구매 수량 1개만 지원합니다. 상품 수정에서 수량을 1개로 바꿔 주세요.", color = MaterialTheme.colorScheme.error)
                    Text("상품명", style = MaterialTheme.typography.labelLarge)
                    Text(item.title, modifier = Modifier.testTag("saved-purchase-title"))
                    Text("옵션", style = MaterialTheme.typography.labelLarge)
                    Text(item.purchaseOption ?: "미설정", modifier = Modifier.testTag("saved-purchase-option"))
                    Text("배송비 포함 최종 금액 한도", style = MaterialTheme.typography.labelLarge)
                    Text(item.maxTotalWon?.let(::won) ?: "미설정", modifier = Modifier.testTag("saved-purchase-limit"))
                    Text("상품 등록·수정에서 함께 저장한 조건을 사용합니다. 이미지를 다시 읽거나 같은 값을 재입력할 필요가 없습니다.", style = MaterialTheme.typography.bodySmall)
                    purchaseConditionProblem(item)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("edit-purchase-conditions"), onClick = { onEditItem(item.id) }) {
                        Text("상품·구매 조건 수정")
                    }
                    Text("시험은 상품 화면의 ‘바로구매’를 눌러 주문 화면으로 이동합니다. 장바구니에 추가하지 않으며 최종 결제 버튼은 누르지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    Text("기기 화면이 켜져 있고 잠금이 풀려 있으며 쿠팡에 로그인되어 있어야 합니다. 로그인·추가 인증은 직접 진행해야 합니다. 특정 배송 시간대를 예약하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    if (!serviceEnabled) {
                        Text("Android 접근성 설정에서 ‘다시담기 구매 도우미’를 직접 켠 뒤 돌아와 시험 버튼을 눌러 주세요.", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy, onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: ActivityNotFoundException) {
                                error = "Android 설정에서 접근성 메뉴를 직접 열어 주세요."
                            } catch (_: SecurityException) {
                                error = "접근성 설정을 직접 열어 주세요."
                            }
                        }) { Text("접근성 설정 열기") }
                    }
                    if (!AutoBuyController.checkoutAdapterVerified) Text("이 쿠팡 버전의 결제 화면 검증이 완료되지 않았습니다. 확인 가능한 단계까지만 시험하며 실제 결제는 계속 차단합니다.", style = MaterialTheme.typography.bodySmall)
                }
                if (busy) CircularProgressIndicator()
                if (ownsPreview) when (val current = runtime) {
                    AutoBuyRuntimeState.Idle -> Unit
                    is AutoBuyRuntimeState.Running -> Text(current.message, style = MaterialTheme.typography.bodySmall)
                    is AutoBuyRuntimeState.Finished -> Text(outcomeMessage(current.outcome), style = MaterialTheme.typography.bodySmall)
                }
                lastAttempt?.let { attempt ->
                    Text("마지막 시험: ${statusLabel(attempt.status)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(attempt.message, style = MaterialTheme.typography.bodySmall)
                    Text(Instant.ofEpochMilli(attempt.updatedAtEpochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.KOREA)), style = MaterialTheme.typography.labelSmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (running) {
                TextButton(onClick = { AutoBuyController.cancel() }) { Text("시험 중단") }
            } else {
                Button(enabled = !busy && selected?.quantity == 1 && serviceEnabled && selected?.let(::purchaseConditionProblem) == null, onClick = {
                    val item = selected ?: return@Button
                    val limit = item.maxTotalWon
                    val option = item.purchaseOption
                    error = purchaseConditionProblem(item)
                    if (error == null && limit != null && option != null) {
                        saving = true
                        ownsPreview = false
                        scope.launch {
                            try {
                                val saved = store.saveProfile(AutoBuyProfile(
                                    itemId = item.id,
                                    expectedTitle = item.title,
                                    expectedOption = option.trim(),
                                    maxTotalWon = limit,
                                    productUrlSnapshot = item.productUrl,
                                    quantitySnapshot = item.quantity,
                                    enabled = false,
                                ))
                                ownsPreview = true
                                AutoBuyController.startDryRun(context, item, saved)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "시험 조건을 저장하거나 시작하지 못했습니다."
                            } finally {
                                saving = false
                            }
                        }
                    }
                }) { Text("결제 전까지 시험") }
            }
        },
        dismissButton = { TextButton(onClick = ::dismiss, enabled = !saving) { Text("닫기") } },
    )
}

private fun won(amount: Long): String = "${NumberFormat.getIntegerInstance(Locale.KOREA).format(amount)}원"

private fun purchaseConditionProblem(item: RoutineItem): String? = when {
    CartAssistPolicy.normalize(item.title).length < 4 -> "상품 수정에서 쿠팡에 표시되는 상품명을 문자·숫자 4자 이상으로 입력해 주세요."
    CartAssistPolicy.normalize(item.purchaseOption.orEmpty()).length !in 2..120 || item.purchaseOption.orEmpty().length > 120 -> "상품 수정에서 옵션과 금액 한도를 함께 저장해 주세요."
    item.maxTotalWon == null || item.maxTotalWon !in 1L..100_000_000L -> "상품 수정에서 옵션과 금액 한도를 함께 저장해 주세요."
    else -> null
}

private fun outcomeMessage(outcome: AutoBuyOutcome): String = when (outcome) {
    is AutoBuyOutcome.DryRunReady -> "결제 전 확인 완료 · 최종 금액 ${won(outcome.totalWon)}. 실제 결제는 하지 않았습니다."
    is AutoBuyOutcome.Blocked -> "시험 중단: ${outcome.reason}"
    is AutoBuyOutcome.Failed -> "시험 실패: ${outcome.reason}"
    is AutoBuyOutcome.Uncertain -> "결과 확인 필요: ${outcome.reason} 쿠팡에서 직접 확인한 뒤 다시 시험해 주세요."
    is AutoBuyOutcome.Confirmed -> "이전 주문 확인 기록이 있습니다. 현재 버전의 실제 결제는 비활성화되어 있습니다."
}

private fun statusLabel(status: AutoBuyStatus): String = when (status) {
    AutoBuyStatus.CLAIMED -> "시작 기록됨"
    AutoBuyStatus.BLOCKED -> "차단됨"
    AutoBuyStatus.PREPARING -> "조건 확인 중"
    AutoBuyStatus.PAYMENT_SUBMITTED -> "이전 결제 제출 기록 · 결과 확인 필요"
    AutoBuyStatus.CONFIRMED -> "이전 주문 확인 기록"
    AutoBuyStatus.DRY_RUN_READY -> "결제 전 확인 완료"
    AutoBuyStatus.FAILED -> "실패"
    AutoBuyStatus.UNCERTAIN -> "결과 확인 필요 · 재시도 중단"
}
