package dev.local.rebuy.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.rebuy.cart.CartAdditionLedger
import dev.local.rebuy.cart.CartAssistController
import dev.local.rebuy.cart.CartAssistPolicy
import dev.local.rebuy.cart.CartAssistRequest
import dev.local.rebuy.cart.CartAssistState
import dev.local.rebuy.autobuy.AutoBuyController
import dev.local.rebuy.domain.CoupangUrl
import dev.local.rebuy.domain.CoupangCartProductLink
import dev.local.rebuy.domain.CoupangShareLinkResolver
import dev.local.rebuy.importproduct.IncomingProductImage
import java.io.Closeable

@Composable
internal fun CartAssistDialog(
    initialUrl: String,
    initialTitle: String,
    quantity: Int,
    maxTotalWon: Long?,
    initialOption: String = "",
    incomingImage: IncomingProductImage? = null,
    onIncomingImageConsumed: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val assistState by CartAssistController.state.collectAsStateWithLifecycle()
    val diagnostics by CartAssistController.diagnostics.collectAsStateWithLifecycle()
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    var option by rememberSaveable(initialOption) { mutableStateOf(initialOption) }
    var showCorrection by rememberSaveable(initialUrl, initialTitle, initialOption) {
        mutableStateOf(initialUrl.isBlank() || initialTitle.isBlank() || initialOption.isBlank())
    }
    var serviceEnabled by remember { mutableStateOf(CartAssistController.isEnabled(context)) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var receipt by remember { mutableStateOf<CartAdditionLedger.Record?>(null) }
    var receiptReadFailed by remember { mutableStateOf(false) }
    var showReceiptReset by rememberSaveable { mutableStateOf(false) }
    var ownsAttempt by rememberSaveable { mutableStateOf(false) }
    var resolution by remember { mutableStateOf<CartShareResolution?>(null) }
    var resolutionSequence by remember { mutableLongStateOf(0L) }
    var resolutionWasPending by rememberSaveable { mutableStateOf(false) }
    val running = ownsAttempt && assistState is CartAssistState.Running
    val busy = running || resolution != null

    fun refreshReceipt() {
        receiptReadFailed = false
        receipt = if (runCatching { CoupangCartProductLink.parseForCart(url) }.isSuccess)
            runCatching { CartAdditionLedger(context).lookup(url) }.getOrElse {
                receiptReadFailed = true
                error = "이전 담기 기록을 읽지 못했습니다. 담기는 시작 시 다시 검사하며 기록을 읽지 못하면 중단합니다."
                null
            } else null
    }
    LaunchedEffect(url, assistState) { refreshReceipt() }

    LaunchedEffect(Unit) {
        CartAssistController.restoreDiagnostics(context)
        // A WebView request belongs to the visible activity. Rotation never resumes a click.
        if (resolutionWasPending && resolution == null) {
            resolutionWasPending = false
            error = "공유 링크 확인이 중단되었습니다. 장바구니에 담기를 다시 눌러 주세요."
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = CartAssistController.isEnabled(context)
                refreshReceipt()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun cancelAttempt() {
        if (resolution != null) {
            resolution = null
            resolutionWasPending = false
            error = "공유 링크 확인을 중지했습니다."
        }
        if (ownsAttempt) CartAssistController.cancel()
    }

    LaunchedEffect(incomingImage?.id) {
        if (incomingImage != null) {
            cancelAttempt()
            showCorrection = true
        }
    }

    fun startResolved(canonicalUrl: String, requestTitle: String, requestOption: String) {
        if (CartAssistController.state.value is CartAssistState.Running || AutoBuyController.isActive()) {
            error = "다른 구매 도우미가 실행 중입니다. 실행을 마친 뒤 다시 시작해 주세요."
            return
        }
        val product = runCatching { CoupangCartProductLink.parse(canonicalUrl) }.getOrElse {
            error = "상품 주소를 확인하지 못했습니다. 쿠팡 상품의 전체 주소로 다시 시도해 주세요."
            return
        }
        url = product.canonicalUrl
        ownsAttempt = CartAssistController.start(context, CartAssistRequest(product.canonicalUrl, requestTitle, requestOption, maxTotalWon = maxTotalWon))
        serviceEnabled = CartAssistController.isEnabled(context)
        if (!ownsAttempt) error = (CartAssistController.state.value as? CartAssistState.Finished)?.message
            ?: "구매 도우미를 시작하지 못했습니다. 잠시 뒤 다시 시도해 주세요."
    }

    fun beginAttempt() {
        if (busy) return
        error = when {
            url.isBlank() -> "쿠팡 상품 URL을 입력해 주세요."
            title.trim().length > 250 || CartAssistPolicy.normalize(title).length < 4 -> "상품명은 문자·숫자 4자 이상, 전체 250자 이내로 입력해 주세요."
            option.trim().length > 120 || CartAssistPolicy.normalize(option).length < 2 -> "옵션은 문자·숫자 2자 이상, 전체 120자 이내로 입력해 주세요."
            else -> runCatching { CoupangUrl.normalize(url) }.exceptionOrNull()?.message
        }
        if (error != null) return
        if (CartAssistController.state.value is CartAssistState.Running || AutoBuyController.isActive()) {
            error = "다른 구매 도우미가 실행 중입니다. 실행을 마친 뒤 다시 시작해 주세요."
            return
        }
        ownsAttempt = false
        val requestUrl = CoupangUrl.normalize(url)
        val requestTitle = title.trim()
        val requestOption = option.trim()
        val product = runCatching { CoupangCartProductLink.parse(requestUrl) }.getOrNull()
        if (product != null) {
            startResolved(product.canonicalUrl, requestTitle, requestOption)
        } else {
            resolutionSequence += 1
            resolutionWasPending = true
            resolution = CartShareResolution(resolutionSequence, requestUrl, requestTitle, requestOption)
        }
    }

    fun dismiss() {
        cancelAttempt()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("장바구니 담기") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("등록한 링크의 상품과 선택 옵션을 쿠팡 장바구니에 담습니다.")
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title.ifBlank { "상품 정보를 먼저 확인해 주세요" }, fontWeight = FontWeight.SemiBold)
                        Text("선택 옵션  ${option.ifBlank { "확인 필요" }}", style = MaterialTheme.typography.bodyMedium)
                        Text(url.ifBlank { "상품 링크 필요" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (runCatching { CoupangCartProductLink.parseForCart(url).itemId == null }.getOrDefault(false)) {
                    Text("옵션 번호가 없는 링크입니다. 쿠팡 본문의 상품명과 선택 규격이 모두 일치할 때만 담습니다.", style = MaterialTheme.typography.bodySmall)
                }
                Text("앱의 이전 담기 요청 기록이 있으면 같은 옵션을 다시 담지 않습니다. 쿠팡에서 직접 담은 상품은 확인할 수 없으니 장바구니를 함께 확인해 주세요.", style = MaterialTheme.typography.bodySmall)
                Text("수량 1개를 담습니다. 등록 한도가 있으면 화면의 상품가를 비교하고, 초과하거나 가격이 불명확하면 담지 않습니다. 배송비·최종 결제 금액은 검증하지 않으며 주문·결제·구매 완료 기록을 실행하지 않습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                maxTotalWon?.let { Text("상품가 비교 한도 ${java.text.NumberFormat.getIntegerInstance(java.util.Locale.KOREA).format(it)}원", style = MaterialTheme.typography.labelLarge) }
                receipt?.let { previous ->
                    Text(if (previous.status == CartAdditionLedger.Status.CONFIRMED) "이 상품의 담기 완료 기록이 있어 재추가를 막고 있습니다." else "이 상품의 이전 담기 요청 결과를 확인해야 합니다. 재추가를 막고 있습니다.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                if (receipt != null || receiptReadFailed) TextButton(onClick = { error = null; showReceiptReset = true }, enabled = !busy, modifier = Modifier.testTag("cart-record-reset")) { Text("장바구니 확인 후 기록 해제") }
                if (quantity != 1) Text(
                    "상품 수정에서 구매 수량을 1개로 바꿔 주세요.",
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { showCorrection = !showCorrection }, enabled = !busy, modifier = Modifier.testTag("cart-details-edit")) {
                    Text(if (showCorrection) "정보 보완 닫기" else "정보 보완")
                }
                if (showCorrection) {
                    Text("링크가 우선입니다. 정보가 다르면 상품 수정에서 링크로 다시 불러오거나 아래에서 이번 담기의 정보만 보완해 주세요.", style = MaterialTheme.typography.bodySmall)
                    ProductImageImport(
                        enabled = !busy,
                        includePrice = false,
                        incomingImage = incomingImage,
                        onIncomingImageConsumed = onIncomingImageConsumed,
                    ) { draft ->
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
                }
                if (!serviceEnabled) {
                    Text("Android 접근성 설정에서 ‘다시담기 구매 도우미’를 켠 뒤 돌아와 장바구니에 담기를 눌러 주세요.", style = MaterialTheme.typography.bodySmall)
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
                resolution?.let { request ->
                    CartShareResolutionPanel(
                        request = request,
                        onResolved = { canonicalUrl ->
                            if (resolution?.id == request.id) {
                                resolution = null
                                resolutionWasPending = false
                                startResolved(canonicalUrl, request.title, request.option)
                            }
                        },
                        onFailure = { message ->
                            if (resolution?.id == request.id) {
                                resolution = null
                                resolutionWasPending = false
                                error = message
                            }
                        },
                    )
                }
                if (ownsAttempt) when (val result = assistState) {
                    CartAssistState.Idle -> Unit
                    is CartAssistState.Running -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(result.message, style = MaterialTheme.typography.bodySmall)
                    }
                    is CartAssistState.Finished -> {
                        Text(if (result.success) "담기 완료" else "담기를 마치지 못했습니다",
                            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(result.message, color = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (diagnostics.stage != dev.local.rebuy.cart.CartAssistStage.IDLE) {
                    TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                        Text(if (showDiagnostics) "상세 실행 기록 닫기" else "상세 실행 기록")
                    }
                    if (showDiagnostics) {
                        val diagnosticText = diagnostics.toDiagnosticText()
                        SelectionContainer {
                            Text(diagnosticText, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                ClipData.newPlainText("다시담기 실행 진단", diagnosticText),
                            )
                        }) { Text("진단 내용 복사") }
                    }
                }
            }
        },
        confirmButton = {
            if (busy) {
                TextButton(onClick = ::cancelAttempt) { Text("실행 중단") }
            } else {
                Button(
                    enabled = quantity == 1 && serviceEnabled && receipt == null && !receiptReadFailed,
                    onClick = ::beginAttempt,
                    modifier = Modifier.fillMaxWidth().testTag("cart-add-one"),
                ) {
                    Text(if (receipt != null || receiptReadFailed) "이전 담기 기록 확인 필요" else "장바구니에 담기")
                }
            }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text("닫기") } },
    )
    if (showReceiptReset) AlertDialog(
        onDismissRequest = { showReceiptReset = false },
        title = { Text("담기 기록을 해제할까요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("쿠팡 장바구니에서 이 상품을 삭제했거나 주문한 것을 직접 확인한 경우에만 해제해 주세요. 기록만 지우며 쿠팡 장바구니는 변경하지 않습니다. 상품이 남아 있으면 다시 담을 때 수량이 늘어날 수 있습니다.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (CartAssistController.clearRecordedAddition(context, url)) {
                    ownsAttempt = false
                    refreshReceipt()
                    error = when {
                        receiptReadFailed -> "남아 있는 담기 기록을 확인하지 못했습니다. 담기는 계속 차단됩니다."
                        receipt != null -> "같은 상품의 다른 링크로 남은 담기 기록이 있습니다. 해당 링크의 담기 창에서 확인 후 해제해 주세요."
                        else -> null
                    }
                    showReceiptReset = false
                } else error = "기록을 해제하지 못했습니다. 실행 중인 도우미를 닫고 다시 시도해 주세요."
            }, enabled = !busy, modifier = Modifier.testTag("cart-record-clear-confirm")) { Text("확인했어요 · 기록 해제") }
        },
        dismissButton = { TextButton(onClick = { showReceiptReset = false }) { Text("취소") } },
    )

}

internal data class CartShareResolution(val id: Long, val url: String, val title: String, val option: String)

@Composable
internal fun CartShareResolutionPanel(request: CartShareResolution, onResolved: (String) -> Unit, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val resolvedCallback by rememberUpdatedState(onResolved)
    val failureCallback by rememberUpdatedState(onFailure)
    val webView = remember(request.id, context) { runCatching { WebView(context) }.getOrNull() }
    if (webView == null) {
        LaunchedEffect(request.id) { failureCallback("공유 링크 확인 화면을 열 수 없습니다. 쿠팡 상품의 전체 주소를 붙여 넣어 주세요.") }
        return
    }
    DisposableEffect(webView, request.id) {
        var active = true
        var handle: Closeable? = null
        try {
            handle = CoupangShareLinkResolver.start(webView, request.url,
                onResolved = { result ->
                    if (active) {
                        active = false
                        runCatching { handle?.close() }
                        resolvedCallback(result)
                    }
                },
                onFailure = { message ->
                    if (active) {
                        active = false
                        runCatching { handle?.close() }
                        failureCallback(message)
                    }
                },
            )
        } catch (_: Exception) {
            active = false
            failureCallback("공유 링크를 확인하지 못했습니다. 쿠팡 상품의 전체 주소로 다시 시도해 주세요.")
        }
        onDispose {
            active = false
            runCatching { handle?.close() }
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("cart-share-resolution")) {
        Text("공유 링크에서 상품 주소를 확인하고 있어요.", style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().height(220.dp).testTag("cart-share-webview"))
    }
}
