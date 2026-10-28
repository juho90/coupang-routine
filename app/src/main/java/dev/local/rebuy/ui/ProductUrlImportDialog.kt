package dev.local.rebuy.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.rebuy.cart.CartAssistController
import dev.local.rebuy.domain.CoupangCartProductLink
import dev.local.rebuy.domain.CoupangUrl
import dev.local.rebuy.domain.ProductVariant
import dev.local.rebuy.domain.ProductVariantCodec
import dev.local.rebuy.importproduct.ProductLinkImportController
import dev.local.rebuy.importproduct.ProductLinkImportState
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun ProductUrlImportDialog(
    rawInitialUrl: String,
    onApply: (ProductVariant, Long?) -> Unit,
    onDismiss: () -> Unit,
    onOpenChanged: (Boolean) -> Unit = {},
    autoStart: Boolean = false,
) {
    val context = LocalContext.current
    val importState by ProductLinkImportController.state.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(rawInitialUrl) }
    var requestUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var started by rememberSaveable { mutableStateOf(false) }
    var resolvingWasPending by rememberSaveable { mutableStateOf(false) }
    var resolving by remember { mutableStateOf<CartShareResolution?>(null) }
    var sequence by rememberSaveable { mutableLongStateOf(0) }
    var title by rememberSaveable { mutableStateOf("") }
    var option by rememberSaveable { mutableStateOf("") }
    var populatedFor by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var autoStartConsumed by rememberSaveable { mutableStateOf(false) }
    var showCorrection by rememberSaveable { mutableStateOf(false) }
    val openChanged by rememberUpdatedState(onOpenChanged)
    val reading = started && importState is ProductLinkImportState.Running
    val working = resolving != null || reading
    val result = (importState as? ProductLinkImportState.Finished)
        ?.takeIf { started && it.productUrl == requestUrl }
    val displayedPrice = result?.displayedPriceWon?.takeIf { it in 1L..100_000_000L }
    val priceToApply = displayedPrice?.takeIf {
        val suggestions = result?.suggestions
        suggestions != null &&
            title.trim() == suggestions.titleCandidates.firstOrNull().orEmpty().trim() &&
            option.trim() == suggestions.optionCandidates.firstOrNull().orEmpty().trim()
    }

    DisposableEffect(Unit) {
        openChanged(true)
        onDispose { openChanged(false) }
    }
    LaunchedEffect(Unit) {
        if (resolvingWasPending && resolving == null) {
            resolvingWasPending = false
            error = "화면이 바뀌어 상품 주소 확인을 중단했습니다. 다시 불러와 주세요."
        }
    }
    LaunchedEffect(result, requestUrl) {
        val suggestions = result?.suggestions ?: return@LaunchedEffect
        if (populatedFor != requestUrl) {
            title = suggestions.titleCandidates.firstOrNull().orEmpty()
            option = suggestions.optionCandidates.firstOrNull().orEmpty()
            showCorrection = title.isBlank() || option.isBlank()
            populatedFor = requestUrl
        }
    }
    fun startRead(canonical: String) {
        resolving = null
        resolvingWasPending = false
        requestUrl = canonical
        started = true
        populatedFor = null
        title = ""
        option = ""
        showCorrection = false
        if (!ProductLinkImportController.start(context, canonical)) {
            val failure = ProductLinkImportController.state.value as? ProductLinkImportState.Finished
            started = false
            error = failure?.message ?: "상품 정보 읽기를 시작하지 못했습니다. 접근성 설정을 확인해 주세요."
        }
    }
    fun stop() {
        resolving = null
        resolvingWasPending = false
        if (started && ProductLinkImportController.isActive()) ProductLinkImportController.cancel()
    }
    fun close() {
        stop()
        onDismiss()
    }

    fun beginRead() {
        error = null
        val normalized = runCatching { CoupangUrl.normalize(url) }.getOrNull()
        if (normalized == null) error = "쿠팡 상품 링크를 입력해 주세요."
        else {
            val direct = runCatching { CoupangCartProductLink.parse(normalized) }.getOrNull()
            if (direct != null) startRead(direct.canonicalUrl)
            else {
                started = false
                sequence += 1
                resolvingWasPending = true
                resolving = CartShareResolution(sequence, normalized, "", "")
            }
        }
    }
    LaunchedEffect(autoStart) {
        if (autoStart && !autoStartConsumed) {
            autoStartConsumed = true
            beginRead()
        }
    }

    AlertDialog(
        onDismissRequest = ::close,
        title = { Text("상품 정보 불러오기") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("쿠팡 앱에서 링크의 상품 화면을 읽어 상품명·현재 선택 옵션·상품가를 제안합니다. 담기·주문·결제는 하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; started = false; requestUrl = null; populatedFor = null; error = null },
                    label = { Text("쿠팡 상품 또는 공유 URL") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    minLines = 2, maxLines = 4, enabled = !working,
                    modifier = Modifier.fillMaxWidth().testTag("product-url-import-url"),
                )
                Button(onClick = ::beginRead, enabled = !working && url.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("product-url-import-read")) {
                    Text(if (result == null) "상품 불러오기" else "다시 불러오기")
                }
                resolving?.let { request ->
                    CartShareResolutionPanel(request, onResolved = ::startRead, onFailure = {
                        resolving = null
                        resolvingWasPending = false
                        error = it
                    })
                }
                if (reading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text((importState as ProductLinkImportState.Running).message)
                    Text("읽기가 끝나면 다시담기로 돌아옵니다. 돌아오지 않으면 다시담기를 열어 결과를 확인해 주세요.", style = MaterialTheme.typography.bodySmall)
                }
                result?.let { finished ->
                    if (finished.suggestions == null) Text(finished.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    finished.suggestions?.let { suggestions ->
                        Text("링크의 상품과 옵션이 맞는지 확인해 주세요.", style = MaterialTheme.typography.bodySmall)
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(title.ifBlank { "상품명 확인 필요" }, style = MaterialTheme.typography.titleMedium)
                                Text("선택 옵션 · ${option.ifBlank { "확인 필요" }}")
                                if (displayedPrice != null) {
                                    Text("읽은 상품가 · ${NumberFormat.getIntegerInstance(Locale.KOREA).format(displayedPrice)}원", modifier = Modifier.testTag("product-url-import-price"))
                                    Text("읽은 가격을 확인한 뒤 적용해 주세요. 배송비는 포함하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                                    if (priceToApply == null) Text("상품명이나 옵션을 보완한 경우 읽은 가격을 자동 적용하지 않습니다. 금액 한도를 직접 확인해 주세요.", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text("상품가를 확인하지 못했습니다. 금액 한도는 직접 입력해 주세요.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("product-url-import-price-missing"))
                                }
                            }
                        }
                        if (!showCorrection) TextButton(onClick = { showCorrection = true }) { Text("읽은 정보 보완") }
                        if (showCorrection) {
                            OutlinedTextField(title, { title = it; error = null }, label = { Text("상품명") }, minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth().testTag("product-url-import-title"))
                            if (suggestions.titleCandidates.size > 1) suggestions.titleCandidates.take(6).forEach { candidate -> TextButton(onClick = { title = candidate }) { Text(candidate) } }
                            OutlinedTextField(option, { option = it; error = null }, label = { Text("현재 선택 옵션") }, minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth().testTag("product-url-import-option"))
                            if (suggestions.optionCandidates.size > 1) suggestions.optionCandidates.take(8).forEach { candidate -> TextButton(onClick = { option = candidate }) { Text(candidate) } }
                        }
                        Text("다른 옵션은 해당 옵션의 링크를 카드에 추가해 선택할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!working && !CartAssistController.isEnabled(context)) TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        .onFailure { error = "Android 설정에서 접근성 메뉴를 직접 열어 주세요." }
                }) { Text("접근성 설정 열기") }
            }
        },
        confirmButton = {
            if (!working && result?.suggestions != null) Button(enabled = title.isNotBlank() && option.isNotBlank(), onClick = {
                try {
                    val variant = ProductVariantCodec.validate(listOf(ProductVariant(title.trim(), option.trim(), requireNotNull(requestUrl)))).single()
                    onApply(variant, priceToApply)
                } catch (invalid: IllegalArgumentException) {
                    error = invalid.message ?: "상품명·옵션·상품 주소를 확인해 주세요."
                }
            }, modifier = Modifier.testTag("product-url-import-apply")) { Text("상품 정보 적용") }
            else if (working) TextButton(onClick = { stop(); error = "상품 정보 읽기를 중단했습니다." }) { Text("읽기 중단") }
        },
        dismissButton = { TextButton(onClick = ::close) { Text("닫기") } },
    )
}
