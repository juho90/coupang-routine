package dev.local.rebuy.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.rebuy.domain.CoupangUrl
import dev.local.rebuy.importproduct.ClipboardProductImage
import dev.local.rebuy.importproduct.IncomingProductImage
import dev.local.rebuy.importproduct.ProductImageReader
import dev.local.rebuy.importproduct.ProductTextSuggestions
import dev.local.rebuy.pcimport.PcImageInbox
import java.text.NumberFormat
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** A reviewed form draft only. Empty values mean that the image supplied no value for that field. */
internal data class ProductImageDraft(
    val title: String,
    val option: String,
    val displayedPriceWon: Long?,
    val productUrl: String?,
)

@Composable
internal fun ProductImageImport(
    enabled: Boolean,
    includeOption: Boolean = true,
    includePrice: Boolean = true,
    readClipboardImage: (() -> Uri)? = null,
    incomingImage: IncomingProductImage? = null,
    onIncomingImageConsumed: () -> Unit = {},
    onApply: (ProductImageDraft) -> Unit,
) {
    val context = LocalContext.current
    var imageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var imageSelectionId by rememberSaveable { mutableIntStateOf(0) }
    var clipboardSource by rememberSaveable { mutableStateOf(false) }
    var reviewKey by rememberSaveable { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<ProductTextSuggestions?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    fun beginImageRead(uri: Uri, fromClipboard: Boolean) {
        if (uri.scheme != "content") {
            error = if (fromClipboard) "클립보드 이미지를 열 수 없습니다. 이미지를 다시 복사하거나 사진에서 선택해 주세요."
                else "이 이미지를 열 수 없습니다. 사진 선택 화면에서 다른 이미지를 선택해 주세요."
            return
        }
        // Keep the preceding review intact until a new read succeeds.
        imageUri?.takeIf { it != uri.toString() }?.let { PcImageInbox.release(context, Uri.parse(it)) }
        error = null
        clipboardSource = fromClipboard
        imageUri = uri.toString()
        imageSelectionId += 1
        loading = true
    }
    LaunchedEffect(incomingImage?.id, enabled) {
        val request = incomingImage ?: return@LaunchedEffect
        if (!enabled) return@LaunchedEffect
        beginImageRead(request.uri, fromClipboard = false)
        onIncomingImageConsumed()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // Cancelling selection leaves both the existing parent form and the previous UI untouched.
        if (uri != null && enabled) beginImageRead(uri, fromClipboard = false)
    }
    LaunchedEffect(imageUri, imageSelectionId) {
        val requestedUri = imageUri ?: return@LaunchedEffect
        val requestedSelectionId = imageSelectionId
        val requestedClipboard = clipboardSource
        loading = true
        try {
            val result = ProductImageReader.read(context.applicationContext, Uri.parse(requestedUri))
            if (imageUri == requestedUri && imageSelectionId == requestedSelectionId) {
                reviewKey = "$requestedUri#$requestedSelectionId"
                suggestions = result
            }
        } catch (_: TimeoutCancellationException) {
            if (imageUri == requestedUri && imageSelectionId == requestedSelectionId) error = if (requestedClipboard)
                "클립보드 이미지를 읽는 시간이 길어져 중단했습니다. 이미지를 다시 복사하거나 사진에서 선택해 주세요."
                else "이미지를 읽는 시간이 길어져 중단했습니다. 선명한 상품 이미지로 다시 시도해 주세요."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            if (imageUri == requestedUri && imageSelectionId == requestedSelectionId) error = if (requestedClipboard)
                "클립보드 이미지에 접근할 수 없습니다. 이미지를 다시 복사하거나 사진에서 선택해 주세요."
                else "사진을 읽을 수 없습니다. 사진 선택 화면에서 다시 선택해 주세요."
        } catch (invalid: IllegalArgumentException) {
            if (imageUri == requestedUri && imageSelectionId == requestedSelectionId) error = if (requestedClipboard)
                "${invalid.message ?: "클립보드 이미지를 읽을 수 없습니다."} 이미지를 다시 복사하거나 사진에서 선택해 주세요."
                else invalid.message ?: "선택한 이미지를 읽을 수 없습니다. 다른 상품 이미지로 다시 시도해 주세요."
        } catch (_: IOException) {
            if (imageUri == requestedUri && imageSelectionId == requestedSelectionId) error = if (requestedClipboard)
                "클립보드 이미지를 읽을 수 없습니다. 이미지를 다시 복사하거나 사진에서 선택해 주세요."
                else "사진을 읽을 수 없습니다. 사진 선택 화면에서 다시 선택해 주세요."
        } catch (_: Exception) {
            if (imageUri == requestedUri && imageSelectionId == requestedSelectionId) error = if (requestedClipboard)
                "클립보드 이미지에서 글자를 읽지 못했습니다. 이미지를 다시 복사하거나 사진에서 선택해 주세요."
                else "이미지에서 글자를 읽지 못했습니다. 선명한 상품 화면 이미지로 다시 시도해 주세요."
        } finally {
            if (imageUri == requestedUri && imageSelectionId == requestedSelectionId) loading = false
        }
    }

    fun closeReview() {
        imageUri?.let { PcImageInbox.release(context, Uri.parse(it)) }
        imageUri = null
        suggestions = null
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                try {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: Exception) {
                    error = "사진 선택 화면을 열 수 없습니다. 상품 정보를 직접 입력해 주세요."
                }
            },
            enabled = enabled && !loading,
            modifier = Modifier.fillMaxWidth().testTag("product-image-pick"),
        ) { Text("상품 이미지에서 읽기") }
        OutlinedButton(
            onClick = {
                // Read the clipboard only in this explicit click handler, never during composition.
                try {
                    val uri = readClipboardImage?.invoke() ?: ClipboardProductImage.read(context)
                    beginImageRead(uri, fromClipboard = true)
                } catch (_: SecurityException) {
                    error = "클립보드 이미지에 접근할 수 없습니다. 이미지를 다시 복사하거나 사진에서 선택해 주세요."
                } catch (invalid: IllegalArgumentException) {
                    error = "${invalid.message ?: "클립보드에 붙여넣을 이미지가 없습니다."} 이미지를 다시 복사하거나 사진에서 선택해 주세요."
                } catch (_: Exception) {
                    error = "클립보드 이미지를 읽을 수 없습니다. 이미지를 다시 복사하거나 사진에서 선택해 주세요."
                }
            },
            enabled = enabled && !loading,
            modifier = Modifier.fillMaxWidth().testTag("product-image-paste"),
        ) { Text("Android 클립보드 이미지 붙여넣기") }
        Text("PC에서 캡처했다면 PC 전송기에서 ‘클립보드 이미지 보내기’를 눌러 주세요. 이 화면으로 인식 결과를 가져옵니다.", style = MaterialTheme.typography.bodySmall)
        Text("이미지는 기기에서 읽습니다. PC 전송본은 검토가 끝날 때까지 앱 내부에 잠시 보관합니다. Google ML Kit는 성능·사용 통계를 전송할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
        if (loading) Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.testTag("product-image-progress"),
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text("이미지에서 글자를 읽고 있어요.", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = ::closeReview, modifier = Modifier.testTag("product-image-read-cancel")) { Text("중단") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("product-image-error")) }
    }

    suggestions?.let { result ->
        ProductImageReview(
            key = reviewKey,
            suggestions = result,
            enabled = enabled && !loading,
            includeOption = includeOption,
            includePrice = includePrice,
            onDismiss = ::closeReview,
            onApply = { draft ->
                onApply(draft)
                closeReview()
            },
        )
    }
}

@Composable
internal fun ProductImageReview(
    key: String,
    suggestions: ProductTextSuggestions,
    enabled: Boolean,
    includeOption: Boolean = true,
    includePrice: Boolean = true,
    onDismiss: () -> Unit,
    onApply: (ProductImageDraft) -> Unit,
) {
    val titles = remember(suggestions) { suggestions.titleCandidates.filter(String::isNotBlank).distinct() }
    val options = remember(suggestions) { suggestions.optionCandidates.filter(String::isNotBlank).distinct() }
    val urls = remember(suggestions) { suggestions.productUrls.filter(String::isNotBlank).distinct() }
    val prices = remember(suggestions) { suggestions.priceCandidatesWon.filter { it > 0 }.distinct() }
    val unconfirmedNumbers = remember(suggestions) {
        suggestions.unconfirmedNumbers.filter { it > 0 && it !in prices }.distinct()
    }
    var title by rememberSaveable(key) { mutableStateOf(titles.firstOrNull().orEmpty()) }
    var option by rememberSaveable(key) { mutableStateOf(options.firstOrNull().orEmpty()) }
    var url by rememberSaveable(key) { mutableStateOf(urls.firstOrNull().orEmpty()) }
    // A recognized price is never silently selected or treated as spending authorization.
    var price by rememberSaveable(key) { mutableStateOf<Long?>(null) }
    var reviewError by rememberSaveable(key) { mutableStateOf<String?>(null) }
    val hasValue = title.isNotBlank() || (includeOption && option.isNotBlank()) || (includePrice && price != null) || (urls.isNotEmpty() && url.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이미지에서 읽은 상품 정보") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).testTag("product-image-review"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("읽은 내용을 검토하고 수정한 뒤 입력란에 적용해 주세요.", style = MaterialTheme.typography.bodySmall)
                if (titles.isEmpty() && (!includeOption || options.isEmpty()) && (!includePrice || (prices.isEmpty() && unconfirmedNumbers.isEmpty())) && urls.isEmpty()) Text("이미지에서 상품 정보를 찾지 못했습니다. 필요한 내용을 직접 입력하거나 다른 이미지를 선택해 주세요.", style = MaterialTheme.typography.bodySmall)
                ProductCandidateField(
                    value = title,
                    onValueChange = { title = it; reviewError = null },
                    label = "읽은 상품명",
                    candidates = titles,
                    enabled = enabled,
                    tag = "product-image-title",
                )
                if (includeOption) ProductCandidateField(
                    value = option,
                    onValueChange = { option = it; reviewError = null },
                    label = "읽은 옵션",
                    candidates = options,
                    enabled = enabled,
                    tag = "product-image-option",
                )
                if (includePrice) {
                    Text("이미지의 표시가격", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text("선택한 가격은 적용할 때 입력란에 복사됩니다. 읽은 표시가격이며 배송비·최종 금액은 다를 수 있습니다. 저장하거나 주문하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    PriceChoice("가격 적용 안 함", selected = price == null, enabled = enabled, tag = "product-image-price-none", onClick = { price = null })
                    prices.forEach { candidate ->
                        PriceChoice("${NumberFormat.getIntegerInstance(Locale.KOREA).format(candidate)}원", selected = price == candidate, enabled = enabled, tag = "product-image-price-$candidate", onClick = { price = candidate })
                    }
                    if (unconfirmedNumbers.isNotEmpty()) {
                        Text("금액인지 확인이 필요한 숫자", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text("원 표시를 읽지 못한 숫자입니다. 실제 가격인지 확인한 경우에만 원 단위로 사용해 주세요. 추천 상품의 숫자일 수도 있습니다.", style = MaterialTheme.typography.bodySmall)
                        unconfirmedNumbers.forEach { candidate ->
                            PriceChoice("${NumberFormat.getIntegerInstance(Locale.KOREA).format(candidate)}원으로 사용 (통화 미인식)", selected = price == candidate, enabled = enabled, tag = "product-image-number-$candidate", onClick = { price = candidate })
                        }
                    }
                    if (prices.isEmpty() && unconfirmedNumbers.isEmpty()) Text("읽은 가격 후보가 없습니다.", style = MaterialTheme.typography.bodySmall)
                }
                if (urls.isNotEmpty()) ProductCandidateField(
                    value = url,
                    onValueChange = { url = it; reviewError = null },
                    label = "이미지에서 읽은 쿠팡 URL (선택)",
                    candidates = urls,
                    enabled = enabled,
                    tag = "product-image-url",
                ) else Text("이미지에서 상품 URL을 찾지 못했습니다. 원래 입력한 주소는 그대로 유지됩니다.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("product-image-no-url"))
                reviewError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = enabled && hasValue, modifier = Modifier.testTag("product-image-apply"), onClick = {
                val checkedUrl = if (urls.isNotEmpty() && url.isNotBlank()) {
                    try {
                        CoupangUrl.normalize(url)
                    } catch (_: IllegalArgumentException) {
                        reviewError = "읽은 주소를 확인해 주세요. 쿠팡 HTTPS 주소를 입력하거나 주소를 비워서 적용해 주세요."
                        return@Button
                    }
                } else null
                onApply(ProductImageDraft(title.trim(), if (includeOption) option.trim() else "", if (includePrice) price else null, checkedUrl))
            }) { Text("입력란에 적용") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("product-image-review-cancel")) { Text("취소") } },
    )
}

@Composable
private fun ProductCandidateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    candidates: List<String>,
    enabled: Boolean,
    tag: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            minLines = 1,
            maxLines = 4,
            enabled = enabled,
            trailingIcon = if (candidates.size > 1) ({
                TextButton(enabled = enabled, onClick = { expanded = true }, modifier = Modifier.testTag("$tag-candidates")) { Text("후보") }
            }) else null,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 240.dp)) {
            candidates.forEachIndexed { index, candidate ->
                DropdownMenuItem(
                    text = { Text(candidate, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    onClick = { onValueChange(candidate); expanded = false },
                    modifier = Modifier.testTag("$tag-candidate-$index"),
                )
            }
        }
    }
}

@Composable
private fun PriceChoice(label: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
