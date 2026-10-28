@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.local.rebuy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.local.rebuy.data.RoutineItem
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ProductDetailSheet(
    item: RoutineItem,
    busy: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onCartAssist: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp).testTag("product-detail"),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("상품 상세", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (!item.enabled) Text("알림 일시정지 중", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DetailValue("선택한 옵션", item.purchaseOption?.takeIf { it.isNotBlank() } ?: "링크에 지정된 옵션")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DetailValue("다음 구매", LocalDate.ofEpochDay(item.nextPurchaseEpochDay).format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREA)), Modifier.weight(1f))
                DetailValue("수량 · 주기", "${item.quantity}개 · ${item.intervalDays}일마다", Modifier.weight(1f))
            }
            DetailValue("금액 한도", item.maxTotalWon?.let { "${NumberFormat.getIntegerInstance(Locale.KOREA).format(it)}원" } ?: "설정하지 않음")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedButton(
                onClick = onOpen, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("product-open"),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("쿠팡 열기")
                Spacer(Modifier.width(6.dp))
                RebuyIcon(RebuyIconKind.ExternalLink, Modifier.size(16.dp))
            }
            Button(
                onClick = onCartAssist, enabled = !busy && item.quantity == 1,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cart-open"),
                shape = RoundedCornerShape(12.dp),
            ) { Text("장바구니 담기") }
            if (item.quantity != 1) Text("장바구니 담기는 현재 구매 수량 1개를 지원합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
