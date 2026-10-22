package dev.local.rebuy.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { AutoBuyStore(context.applicationContext) }
    val profiles by store.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val attempts by store.attempts.collectAsStateWithLifecycle(initialValue = emptyList())
    val runtime by AutoBuyController.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf(initialItemId ?: items.firstOrNull()?.id) }
    val selected = items.firstOrNull { it.id == selectedId }
    var expanded by remember { mutableStateOf(false) }
    var title by rememberSaveable(selectedId) { mutableStateOf("") }
    var option by rememberSaveable(selectedId) { mutableStateOf("") }
    var amount by rememberSaveable(selectedId) { mutableStateOf("") }
    var loading by remember(selectedId) { mutableStateOf(selected != null) }
    var saving by remember { mutableStateOf(false) }
    var ownsPreview by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable(selectedId) { mutableStateOf<String?>(null) }
    var serviceEnabled by remember { mutableStateOf(CartAssistController.isEnabled(context)) }
    val running = ownsPreview && runtime is AutoBuyRuntimeState.Running
    val busy = loading || saving || running
    val lastAttempt = attempts.filter { it.itemId == selectedId }.maxByOrNull { it.updatedAtEpochMillis }
    val currentStoredProfile = profiles.firstOrNull { it.itemId == selectedId }

    LaunchedEffect(selectedId) {
        val item = selected
        if (item == null) {
            loading = false
            return@LaunchedEffect
        }
        try {
            val saved = store.getProfile(item.id)
            title = saved?.expectedTitle ?: item.title
            option = saved?.expectedOption.orEmpty()
            amount = saved?.maxTotalWon?.toString().orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "저장한 구매 조건을 불러오지 못했습니다."
        } finally {
            loading = false
        }
    }
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
                    ProductImageImport(enabled = !busy) { draft ->
                        if (draft.title.isNotBlank()) title = draft.title
                        if (draft.option.isNotBlank()) option = draft.option
                        draft.displayedPriceWon?.let { amount = it.toString() }
                        error = null
                    }
                    Text("이미지에서 읽은 상품명·옵션·금액은 아래에서 수정할 수 있습니다. 표시가격을 선택했다면 배송비를 포함한 한도로 다시 확인해 주세요. 상품 URL은 구매 목록의 등록 주소를 사용합니다.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it; error = null },
                        label = { Text("쿠팡 화면에 표시되는 상품명 (필수)") },
                        maxLines = 3,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = option,
                        onValueChange = { option = it; error = null },
                        label = { Text("화면에 표시되는 옵션 (필수)") },
                        placeholder = { Text("예: 600ml, 2개") },
                        maxLines = 3,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it.filter(Char::isDigit).take(9); error = null },
                        label = { Text("배송비 포함 최종 금액 한도 (필수)") },
                        suffix = { Text("원") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val missing = buildList {
                        if (CartAssistPolicy.normalize(title).length < 4 || title.trim().length > 150) add("상품명")
                        if (CartAssistPolicy.normalize(option).length < 2 || option.trim().length > 120) add("옵션")
                        if (amount.toLongOrNull() !in 1L..100_000_000L) add("금액 한도")
                    }
                    if (missing.isNotEmpty()) Text("입력 필요: ${missing.joinToString(" · ")}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("상품명과 옵션은 쿠팡에 보이는 그대로 입력해 주세요. 최종 금액이 한도를 넘거나 상품·옵션을 확인할 수 없으면 중단합니다.", style = MaterialTheme.typography.bodySmall)
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
                    currentStoredProfile?.let { saved ->
                        Text("저장한 한도 ${won(saved.maxTotalWon)} · 실제 구매 꺼짐", style = MaterialTheme.typography.bodySmall)
                        if (saved.productUrlSnapshot != item.productUrl || saved.quantitySnapshot != item.quantity) Text("상품 URL 또는 수량이 바뀌었습니다. 시험할 때 현재 조건으로 다시 저장합니다.", style = MaterialTheme.typography.bodySmall)
                    }
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
                Button(enabled = !busy && selected?.quantity == 1 && serviceEnabled, onClick = {
                    val item = selected ?: return@Button
                    val limit = amount.toLongOrNull()
                    error = when {
                        title.trim().length > 150 || CartAssistPolicy.normalize(title).length < 4 -> "상품명은 문자·숫자 4자 이상, 전체 150자 이내로 입력해 주세요."
                        option.trim().length > 120 || CartAssistPolicy.normalize(option).length < 2 -> "옵션은 문자·숫자 2자 이상, 전체 120자 이내로 입력해 주세요."
                        limit == null || limit !in 1L..100_000_000L -> "금액 한도는 1원~1억원으로 입력해 주세요."
                        else -> null
                    }
                    if (error == null && limit != null) {
                        saving = true
                        ownsPreview = false
                        scope.launch {
                            try {
                                val saved = store.saveProfile(AutoBuyProfile(
                                    itemId = item.id,
                                    expectedTitle = title.trim(),
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
