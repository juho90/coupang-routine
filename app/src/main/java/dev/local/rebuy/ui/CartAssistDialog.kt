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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.rebuy.cart.CartAssistController
import dev.local.rebuy.cart.CartAssistPolicy
import dev.local.rebuy.cart.CartAssistRequest
import dev.local.rebuy.cart.CartAssistState
import dev.local.rebuy.domain.CoupangProductLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun CartAssistDialog(
    initialUrl: String,
    initialTitle: String,
    quantity: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val assistState by CartAssistController.state.collectAsStateWithLifecycle()
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    var option by rememberSaveable { mutableStateOf("") }
    var serviceEnabled by remember { mutableStateOf(CartAssistController.isEnabled(context)) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var ownsAttempt by rememberSaveable { mutableStateOf(false) }
    var resolveJob by remember { mutableStateOf<Job?>(null) }
    val running = ownsAttempt && assistState is CartAssistState.Running
    val busy = resolving || running

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = CartAssistController.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun cancelAttempt() {
        resolveJob?.cancel()
        resolveJob = null
        resolving = false
        if (ownsAttempt) CartAssistController.cancel()
    }

    fun dismiss() {
        cancelAttempt()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("장바구니 담기 실험") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("접근성 기능 사용 안내", fontWeight = FontWeight.SemiBold)
                Text(
                    "직접 시작한 동안만 쿠팡 화면의 상품명·옵션·버튼을 읽고, 일치하면 장바구니 담기 버튼을 한 번 누릅니다. 화면 정보는 서버에 보내지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "이미 장바구니에 있는 상품도 1개가 추가됩니다. 결제하거나 구매 완료를 기록하지 않습니다. 성공 여부가 불확실하면 쿠팡 장바구니를 먼저 확인해 주세요.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (quantity != 1) Text(
                    "이 실험은 구매 수량 1개만 지원합니다. 상품 수정에서 수량을 1개로 바꿔 주세요.",
                    color = MaterialTheme.colorScheme.error,
                )
                ProductImageImport(enabled = !busy, includePrice = false) { draft ->
                    if (draft.title.isNotBlank()) title = draft.title
                    if (draft.option.isNotBlank()) option = draft.option
                    draft.productUrl?.let { url = it }
                    error = null
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("쿠팡 상품 또는 공유 URL") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    minLines = 2,
                    maxLines = 4,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    label = { Text("쿠팡 화면에 표시되는 상품명") },
                    minLines = 1,
                    maxLines = 3,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = option,
                    onValueChange = { option = it; error = null },
                    label = { Text("화면에 표시되는 옵션") },
                    placeholder = { Text("예: 600ml, 2개") },
                    minLines = 1,
                    maxLines = 3,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("상품명과 옵션을 쿠팡에 보이는 그대로 입력해 주세요. 등록해 둔 메모 이름과 실제 상품명이 다르면 수정해야 합니다.", style = MaterialTheme.typography.bodySmall)
                if (!serviceEnabled) {
                    Text("Android 접근성 설정에서 ‘다시담기 구매 도우미’를 직접 켠 뒤 돌아와 주세요. 돌아온 뒤 시작 버튼을 다시 눌러야 합니다.", style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: ActivityNotFoundException) {
                                error = "이 기기에서 설정 화면을 열 수 없습니다. Android 설정의 접근성 메뉴를 직접 열어 주세요."
                            } catch (_: SecurityException) {
                                error = "접근성 설정을 직접 열어 주세요."
                            }
                        },
                    ) { Text("접근성 설정 열기") }
                }
                if (resolving) {
                    CircularProgressIndicator()
                    Text("상품 주소를 확인하고 있어요.", style = MaterialTheme.typography.bodySmall)
                }
                if (ownsAttempt) when (val result = assistState) {
                    CartAssistState.Idle -> Unit
                    is CartAssistState.Running -> Text(result.message, style = MaterialTheme.typography.bodySmall)
                    is CartAssistState.Finished -> Text(
                        result.message,
                        color = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (busy) {
                TextButton(onClick = ::cancelAttempt) { Text("실행 중단") }
            } else {
                Button(
                    enabled = quantity == 1 && serviceEnabled,
                    onClick = {
                        error = when {
                            url.isBlank() -> "쿠팡 상품 URL을 입력해 주세요."
                            title.trim().length > 250 || CartAssistPolicy.normalize(title).length < 4 -> "상품명은 문자·숫자 4자 이상, 전체 250자 이내로 입력해 주세요."
                            option.trim().length > 120 || CartAssistPolicy.normalize(option).length < 2 -> "옵션은 문자·숫자 2자 이상, 전체 120자 이내로 입력해 주세요."
                            else -> null
                        }
                        if (error == null) {
                            resolving = true
                            ownsAttempt = false
                            resolveJob = scope.launch {
                                try {
                                    val product = CoupangProductLinks.resolve(url.trim())
                                    ownsAttempt = true
                                    CartAssistController.start(context, CartAssistRequest(product.nativeUri, title.trim(), option.trim()))
                                    serviceEnabled = CartAssistController.isEnabled(context)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "상품 주소를 확인하지 못했습니다. 쿠팡 상품 URL을 확인해 주세요."
                                } finally {
                                    resolving = false
                                    resolveJob = null
                                }
                            }
                        }
                    },
                ) { Text("1개 추가 시작") }
            }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text("닫기") } },
    )
}
