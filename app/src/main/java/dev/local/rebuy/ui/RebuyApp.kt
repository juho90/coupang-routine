@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.local.rebuy.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.rebuy.NotificationCompletionRequest
import dev.local.rebuy.autobuy.AutoBuyStore
import dev.local.rebuy.autobuy.AutoBuyController
import dev.local.rebuy.cart.CartAssistPolicy
import dev.local.rebuy.cart.CartAssistController
import dev.local.rebuy.data.PurchaseRecord
import dev.local.rebuy.data.RoutineItem
import dev.local.rebuy.domain.CoupangUrl
import dev.local.rebuy.importproduct.IncomingProductImage
import dev.local.rebuy.settings.ReminderSettings
import java.time.Instant
import java.time.LocalDate
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

private val dateFormat = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREA)
private val historyFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.KOREA)
private val lightColors = lightColorScheme(
    primary = Color(0xFF146653), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F1E6), onPrimaryContainer = Color(0xFF104F41),
    secondary = Color(0xFF54665E), background = Color(0xFFF7FAF7),
    surface = Color(0xFFFCFDF9), surfaceVariant = Color(0xFFE7EDE7),
)
private val darkColors = darkColorScheme(
    primary = Color(0xFF9AD6BE), onPrimary = Color(0xFF003829),
    primaryContainer = Color(0xFF18503F), onPrimaryContainer = Color(0xFFB6F2D8),
)

@Composable
fun RebuyApp(
    viewModel: RoutineViewModel,
    completionRequest: NotificationCompletionRequest?,
    onCompletionRequestConsumed: () -> Unit,
    openProduct: (String) -> Boolean,
    incomingImage: IncomingProductImage? = null,
    onIncomingImageConsumed: () -> Unit = {},
) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColors else lightColors) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val busy by viewModel.busy.collectAsStateWithLifecycle()
        val completion by viewModel.completionPrompt.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        var today by remember { mutableLongStateOf(LocalDate.now().toEpochDay()) }
        var notificationsAllowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
        var permissionRequested by rememberSaveable { mutableStateOf(false) }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
            viewModel.notificationPermissionChanged()
        }
        fun openNotificationSettings() {
            try {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            } catch (_: android.content.ActivityNotFoundException) {
                scope.launch { snackbar.showSnackbar("기기 설정에서 다시담기의 알림을 켜 주세요") }
            }
        }
        val requestPermission: () -> Unit = {
            if (Build.VERSION.SDK_INT >= 33 && !permissionRequested && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionRequested = true
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else openNotificationSettings()
        }
        DisposableEffect(lifecycleOwner, context) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    today = LocalDate.now().toEpochDay()
                    notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
                    viewModel.notificationPermissionChanged()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(lifecycleOwner, context) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var midnightJob: Job? = null
                fun refreshClock() {
                    today = LocalDate.now().toEpochDay()
                    midnightJob?.cancel()
                    midnightJob = launch {
                        while (true) {
                            val now = ZonedDateTime.now()
                            today = now.toLocalDate().toEpochDay()
                            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                            delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1L))
                        }
                    }
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) { refreshClock() }
                }
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_DATE_CHANGED)
                }
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                try {
                    refreshClock()
                    awaitCancellation()
                } finally {
                    midnightJob?.cancel()
                    context.unregisterReceiver(receiver)
                }
            }
        }
        LaunchedEffect(viewModel) { viewModel.events.collect { snackbar.showSnackbar(it) } }
        LaunchedEffect(completionRequest) {
            completionRequest?.let {
                viewModel.requestCompletion(it.itemId, it.cycleId)
                onCompletionRequestConsumed()
            }
        }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var showEditor by rememberSaveable { mutableStateOf(false) }
        var editorId by rememberSaveable { mutableStateOf<String?>(null) }
        var archiveId by rememberSaveable { mutableStateOf<String?>(null) }
        var showCartAssist by rememberSaveable { mutableStateOf(false) }
        var cartAssistUrl by rememberSaveable { mutableStateOf("") }
        var cartAssistTitle by rememberSaveable { mutableStateOf("") }
        var cartAssistOption by rememberSaveable { mutableStateOf("") }
        var cartAssistQuantity by rememberSaveable { mutableIntStateOf(1) }
        var showAutoBuy by rememberSaveable { mutableStateOf(false) }
        var autoBuyItemId by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(incomingImage?.id, state.loading, state.loadError, busy) {
            if (incomingImage != null && !state.loading && state.loadError == null && !busy) {
                if (showCartAssist) {
                    // Keep the cart URL and unsaved conditions while its image review opens.
                    CartAssistController.cancel()
                } else {
                    // Sharing into an open editor preserves its unsaved fields and selected item.
                    if (!showEditor) editorId = null
                    if (showAutoBuy) AutoBuyController.cancel()
                    showAutoBuy = false
                    archiveId = null
                    viewModel.dismissCompletion()
                    tab = 0
                    showEditor = true
                }
            }
        }
        val onAutoBuy: (RoutineItem?) -> Unit = { item ->
            autoBuyItemId = item?.id
            showAutoBuy = true
        }
        val onCartAssist: (RoutineItem?) -> Unit = { item ->
            cartAssistUrl = item?.productUrl.orEmpty()
            cartAssistTitle = item?.title.orEmpty()
            cartAssistOption = item?.purchaseOption.orEmpty()
            cartAssistQuantity = item?.quantity ?: 1
            showCartAssist = true
        }
        val onOpen: (String) -> Unit = { url ->
            if (!openProduct(url)) scope.launch { snackbar.showSnackbar("쿠팡 URL을 열 수 없습니다. 브라우저 설치와 상품 주소를 확인해 주세요") }
        }
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("다시담기", fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp) },
                    actions = { Text("LOCAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 20.dp)) },
                )
            },
            bottomBar = {
                NavigationBar {
                    listOf("구매 목록" to "▤", "구매 이력" to "✓", "설정" to "⚙").forEachIndexed { index, (label, symbol) ->
                        NavigationBarItem(
                            selected = tab == index, onClick = { tab = index },
                            icon = { Text(symbol, fontSize = 22.sp) }, label = { Text(label) },
                        )
                    }
                }
            },
            floatingActionButton = {
                if (tab == 0 && !state.loading && state.loadError == null) ExtendedFloatingActionButton(
                    onClick = { editorId = null; showEditor = true },
                    modifier = Modifier.testTag("add-product").semantics { contentDescription = "상품 등록" },
                    icon = { Text("+", fontSize = 25.sp) }, text = { Text("상품 등록") },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.loadError != null -> EmptyState("정보를 불러오지 못했어요", state.loadError!!)
                    tab == 0 -> RoutineList(
                        state.items, today, notificationsAllowed, state.settings.notificationsEnabled, busy,
                        requestPermission,
                        onEdit = { editorId = it.id; showEditor = true },
                        onToggle = { viewModel.setEnabled(it, !it.enabled) },
                        onArchive = { archiveId = it.id },
                        onOpen = onOpen, onComplete = viewModel::requestCompletion,
                        onCartAssist = { onCartAssist(it) },
                        onAutoBuy = { onAutoBuy(it) },
                    )
                    tab == 1 -> HistoryList(state.history, onOpen)
                    else -> SettingsScreen(state.settings, notificationsAllowed, busy, requestPermission, ::openNotificationSettings, viewModel::updateSettings, { onCartAssist(null) }, { onAutoBuy(null) })
                }
            }
        }
        if (showEditor && !state.loading) ItemEditor(
            item = state.items.firstOrNull { it.id == editorId },
            today = today,
            busy = busy, onDismiss = { if (!busy) showEditor = false },
            incomingImage = incomingImage,
            onIncomingImageConsumed = onIncomingImageConsumed,
            onSave = { title, url, quantity, interval, date, option, limit ->
                viewModel.saveItem(editorId, title, url, quantity, interval, date, option, limit) { showEditor = false }
            },
        )
        if (showCartAssist) CartAssistDialog(
            initialUrl = cartAssistUrl,
            initialTitle = cartAssistTitle,
            initialOption = cartAssistOption,
            quantity = cartAssistQuantity,
            incomingImage = incomingImage,
            onIncomingImageConsumed = onIncomingImageConsumed,
            onDismiss = { showCartAssist = false },
        )
        if (showAutoBuy) AutoBuyDialog(
            items = state.items.filterNot { it.archived },
            initialItemId = autoBuyItemId,
            reminderSettings = state.settings,
            onDismiss = { showAutoBuy = false },
            onEditItem = { id -> showAutoBuy = false; editorId = id; showEditor = true },
        )
        completion?.let { prompt ->
            AlertDialog(
                onDismissRequest = { if (!busy) viewModel.dismissCompletion() },
                title = { Text("구매를 완료했나요?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${prompt.title} · ${prompt.quantity}개", fontWeight = FontWeight.SemiBold)
                        Text("예정일 ${formatDate(prompt.scheduledEpochDay)}")
                        Text("쿠팡에서 직접 결제한 뒤 확인해 주세요. 구매 이력을 남기고 완료일을 기준으로 다음 구매일을 갱신합니다.")
                        Text("같은 예정 건은 한 번만 기록됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = { Button(onClick = { viewModel.completePurchase(prompt) }, enabled = !busy) { Text("구매 완료 기록") } },
                dismissButton = { TextButton(onClick = viewModel::dismissCompletion, enabled = !busy) { Text("아직이에요") } },
            )
        }
        state.items.firstOrNull { it.id == archiveId }?.let { item ->
            AlertDialog(
                onDismissRequest = { archiveId = null },
                title = { Text("구매 목록에서 보관할까요?") },
                text = { Text("${item.title}의 알림을 종료합니다. 기존 구매 이력은 계속 확인할 수 있습니다.") },
                confirmButton = { TextButton(onClick = { viewModel.archive(item); archiveId = null }, enabled = !busy) { Text("보관") } },
                dismissButton = { TextButton(onClick = { archiveId = null }) { Text("취소") } },
            )
        }
    }
}

@Composable
private fun RoutineList(
    source: List<RoutineItem>, today: Long, notificationsAllowed: Boolean, remindersEnabled: Boolean, busy: Boolean,
    requestPermission: () -> Unit, onEdit: (RoutineItem) -> Unit, onToggle: (RoutineItem) -> Unit,
    onArchive: (RoutineItem) -> Unit, onOpen: (String) -> Unit, onComplete: (RoutineItem) -> Unit,
    onCartAssist: (RoutineItem) -> Unit,
    onAutoBuy: (RoutineItem) -> Unit,
) {
    val all = source.filterNot { it.archived }
    val dueCount = all.count { it.enabled && it.nextPurchaseEpochDay <= today }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val visible = all.filter { when (filter) { 1 -> it.enabled; 2 -> !it.enabled; else -> true } }
        .sortedWith(compareBy<RoutineItem> { !it.enabled }.thenBy { it.nextPurchaseEpochDay }.thenBy { it.title })
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("routine-list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("필요한 날, 다시 담아요", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (dueCount > 0) "오늘 챙길 상품이 ${dueCount}개 있어요." else "자주 사는 상품의 다음 구매일을 챙겨드려요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!notificationsAllowed) item {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("예정일 알림을 켜 주세요", fontWeight = FontWeight.SemiBold)
                    Text("기기에서 알림을 허용하면 구매일에 알려드려요.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = requestPermission, contentPadding = PaddingValues(0.dp)) { Text("알림 허용 / 설정") }
                }
            }
        } else if (!remindersEnabled) item {
            Text("모든 알림이 꺼져 있습니다. 설정에서 다시 켤 수 있어요.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("전체 ${all.size}", "예정 ${all.count { it.enabled }}", "일시정지 ${all.count { !it.enabled }}").forEachIndexed { index, text ->
                    FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(text) })
                }
            }
        }
        if (visible.isEmpty()) item {
            EmptyState(if (all.isEmpty()) "첫 상품을 등록해 보세요" else "이 목록은 아직 비어 있어요", "쿠팡의 상품 또는 공유 URL을 붙여 넣고\n수량과 구매 주기를 정해 주세요.", Modifier.padding(top = 44.dp))
        }
        items(visible, key = { it.id }) { item ->
            RoutineCard(item, today, busy, onEdit, onToggle, onArchive, onOpen, onComplete, onCartAssist, onAutoBuy)
        }
        if (visible.isNotEmpty()) item {
            Text("쿠팡 열기로 직접 구매한 뒤 ‘구매 완료’를 눌러 주세요. 결제는 자동으로 진행되지 않습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun RoutineCard(
    item: RoutineItem, today: Long, busy: Boolean,
    onEdit: (RoutineItem) -> Unit, onToggle: (RoutineItem) -> Unit, onArchive: (RoutineItem) -> Unit,
    onOpen: (String) -> Unit, onComplete: (RoutineItem) -> Unit,
    onCartAssist: (RoutineItem) -> Unit,
    onAutoBuy: (RoutineItem) -> Unit,
) {
    val isDue = item.enabled && item.nextPurchaseEpochDay <= today
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.testTag("routine-card"),
        colors = CardDefaults.cardColors(containerColor = if (isDue) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when { !item.enabled -> "일시정지"; item.nextPurchaseEpochDay < today -> "예정일 지남"; isDue -> "오늘 구매 예정"; else -> "${item.nextPurchaseEpochDay - today}일 뒤" },
                    color = if (isDue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                )
                Box {
                    TextButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.size(40.dp).semantics { contentDescription = "${item.title} 관리" }, contentPadding = PaddingValues(0.dp)) { Text("⋯", fontSize = 24.sp) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("상품 수정") }, onClick = { menu = false; onEdit(item) })
                        DropdownMenuItem(text = { Text(if (item.enabled) "알림 일시정지" else "알림 다시 시작") }, onClick = { menu = false; onToggle(item) })
                        DropdownMenuItem(text = { Text("목록에서 보관") }, onClick = { menu = false; onArchive(item) })
                    }
                }
            }
            Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            item.purchaseOption?.let { Text("옵션  $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item.maxTotalWon?.let { Text("금액 한도  ${NumberFormat.getIntegerInstance(Locale.KOREA).format(it)}원", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("${item.quantity}개  ·  ${item.intervalDays}일마다", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("다음 구매  ${formatDate(item.nextPurchaseEpochDay)}", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onOpen(item.productUrl) }, modifier = Modifier.weight(1f), enabled = !busy) { Text("쿠팡 열기") }
                Button(onClick = { onComplete(item) }, modifier = Modifier.weight(1f), enabled = item.enabled && !busy) { Text("구매 완료") }
            }
            TextButton(onClick = { onCartAssist(item) }, enabled = !busy && item.quantity == 1, contentPadding = PaddingValues(0.dp)) {
                Text("장바구니 담기 실험")
            }
            if (item.quantity != 1) Text("담기 실험은 구매 수량 1개만 지원합니다. 상품 수정에서 수량을 바꿔 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onAutoBuy(item) }, enabled = !busy, contentPadding = PaddingValues(0.dp)) {
                Text("구매 조건 · 결제 전 시험")
            }
        }
    }
}

@Composable
private fun HistoryList(history: List<PurchaseRecord>, onOpen: (String) -> Unit) {
    if (history.isEmpty()) {
        EmptyState("구매 이력이 쌓이는 곳", "쿠팡에서 구매한 뒤 완료를 확인하면\n상품과 수량, 완료 시간이 여기에 남아요.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("나의 구매 기록", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("직접 확인한 구매 ${history.size}건", modifier = Modifier.padding(top = 8.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(history.sortedByDescending { it.completedAtEpochMillis }, key = { it.id }) { record ->
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Instant.ofEpochMilli(record.completedAtEpochMillis).atZone(ZoneId.systemDefault()).format(historyFormat), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(record.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${record.quantity}개 구매 · 예정일 ${formatDate(record.scheduledEpochDay)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onOpen(record.productUrl) }, contentPadding = PaddingValues(0.dp)) { Text("상품 다시 보기") }
                }
            }
        }
        item { Text("기록은 사용자의 완료 확인을 바탕으로 저장됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SettingsScreen(
    settings: ReminderSettings, notificationsAllowed: Boolean, busy: Boolean,
    requestPermission: () -> Unit, openSystemSettings: () -> Unit,
    onSave: (Boolean, Int, Int) -> Unit,
    onCartAssist: () -> Unit,
    onAutoBuy: () -> Unit,
) {
    var enabled by rememberSaveable(settings.notificationsEnabled) { mutableStateOf(settings.notificationsEnabled) }
    var hour by rememberSaveable(settings.hour) { mutableStateOf(settings.hour.toString().padStart(2, '0')) }
    var minute by rememberSaveable(settings.minute) { mutableStateOf(settings.minute.toString().padStart(2, '0')) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("구매일을 챙기는 방법", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("예정일 알림", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("등록한 모든 상품에 적용", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy)
                }
                Text("알림 시간", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = hour, onValueChange = { hour = it.take(2); error = null }, label = { Text("시 (0~23)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                    Text(":")
                    OutlinedTextField(value = minute, onValueChange = { minute = it.take(2); error = null }, label = { Text("분 (0~59)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = {
                    val h = hour.toIntOrNull(); val m = minute.toIntOrNull()
                    if (h == null || h !in 0..23 || m == null || m !in 0..59) error = "올바른 시와 분을 입력해 주세요"
                    else onSave(enabled, h, m)
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("알림 설정 저장") }
                Text("기기의 현재 시간대를 따릅니다. 절전 모드나 기기 상태에 따라 알림이 늦어질 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("기기 알림 ${if (notificationsAllowed) "허용됨" else "꺼짐"}", fontWeight = FontWeight.SemiBold)
                Text(if (notificationsAllowed) "알림이 오지 않으면 기기 설정에서 알림 채널도 확인해 주세요." else "예정일 알림을 받으려면 기기에서도 알림을 허용해 주세요.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = if (notificationsAllowed) openSystemSettings else requestPermission, contentPadding = PaddingValues(0.dp)) { Text(if (notificationsAllowed) "기기 알림 설정 열기" else "알림 허용 / 설정") }
            }
        }
        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("쿠팡 장바구니 담기 실험", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("직접 시작하면 상품과 옵션을 확인한 뒤 장바구니에 1개를 추가합니다. 쿠팡 화면이 바뀌거나 확인할 수 없으면 중단합니다.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCartAssist, enabled = !busy, contentPadding = PaddingValues(0.dp)) { Text("상품 URL로 담기 실험") }
            }
        }
        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("구매 조건 · 결제 전 시험", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("실제 결제는 비활성화되어 있습니다. 상품·옵션·금액 한도를 저장하고 직접 시작한 시험에서 결제 전 단계까지만 확인합니다.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onAutoBuy, enabled = !busy, contentPadding = PaddingValues(0.dp)) { Text("결제 전 시험 설정") }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("나만의 로컬 구매 메모", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("상품과 구매 이력은 이 기기에 저장됩니다. 서버와 로그인 없이 사용하며, 앱을 삭제하면 저장한 정보도 삭제됩니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("다시담기는 쿠팡의 공식 앱이 아닙니다. 상품 가격이나 재고, 주문 결과를 조회하지 않습니다. 쿠팡에서 직접 구매한 뒤 완료를 확인해 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ItemEditor(
    item: RoutineItem?, today: Long, busy: Boolean, onDismiss: () -> Unit,
    incomingImage: IncomingProductImage? = null,
    onIncomingImageConsumed: () -> Unit = {},
    onSave: (String, String, Int, Int, Long, String, Long) -> Unit,
) {
    val context = LocalContext.current
    val key = item?.id ?: "new"
    var title by rememberSaveable(key) { mutableStateOf(item?.title.orEmpty()) }
    var url by rememberSaveable(key) { mutableStateOf(item?.productUrl.orEmpty()) }
    var option by rememberSaveable(key) { mutableStateOf(item?.purchaseOption.orEmpty()) }
    var limit by rememberSaveable(key) { mutableStateOf(item?.maxTotalWon?.toString().orEmpty()) }
    var quantity by rememberSaveable(key) { mutableStateOf((item?.quantity ?: 1).toString()) }
    var interval by rememberSaveable(key) { mutableStateOf((item?.intervalDays ?: 30).toString()) }
    var date by rememberSaveable(key) { mutableLongStateOf(item?.nextPurchaseEpochDay ?: today) }
    var showDatePicker by rememberSaveable(key) { mutableStateOf(false) }
    var error by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var edited by rememberSaveable(key) { mutableStateOf(false) }
    val needsLegacyConditions = item != null && (item.purchaseOption == null || item.maxTotalWon == null)
    var legacyChecked by rememberSaveable(key) { mutableStateOf(!needsLegacyConditions) }
    var legacyLoading by remember(key) { mutableStateOf(!legacyChecked) }
    var legacyNotice by rememberSaveable(key) { mutableStateOf<String?>(null) }
    val inputEnabled = !busy && !legacyLoading
    fun changed() { edited = true; error = null }

    LaunchedEffect(key) {
        val original = item
        if (legacyChecked || !needsLegacyConditions || original == null) {
            legacyLoading = false
            return@LaunchedEffect
        }
        var checked = false
        try {
            val previous = AutoBuyStore(context.applicationContext).getProfile(original.id)
            val matches = previous != null && previous.quantitySnapshot == original.quantity &&
                runCatching { CoupangUrl.normalize(original.productUrl) == previous.productUrlSnapshot }.getOrDefault(false)
            // Restored or edited fields are authoritative. A late local read never replaces them.
            if (!edited && matches && previous != null && url == original.productUrl) {
                if (original.purchaseOption == null && option.isBlank()) option = previous.expectedOption
                if (original.maxTotalWon == null && limit.isBlank()) limit = previous.maxTotalWon.toString()
                if (original.purchaseOption == null && original.maxTotalWon == null && title == original.title && previous.expectedTitle.trim().length in 1..100) {
                    title = previous.expectedTitle.trim()
                }
                legacyNotice = "이전 구매 조건을 불러왔습니다. 구매 옵션과 금액 한도를 확인한 뒤 저장해 주세요."
            } else if (!matches) {
                legacyNotice = "이전 구매 조건을 사용할 수 없습니다. 구매 옵션과 금액 한도를 직접 입력해 주세요."
            }
            checked = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            legacyNotice = "이전 구매 조건을 읽지 못했습니다. 구매 옵션과 금액 한도를 직접 입력해 주세요."
            checked = true
        } finally {
            if (checked) { legacyChecked = true; legacyLoading = false }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "다시 살 상품 등록" else "상품 수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (legacyLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("이전 구매 조건을 확인하고 있어요.", style = MaterialTheme.typography.bodySmall)
                }
                legacyNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                ProductImageImport(
                    enabled = inputEnabled,
                    incomingImage = incomingImage,
                    onIncomingImageConsumed = onIncomingImageConsumed,
                ) { draft ->
                    if (draft.title.isNotBlank()) title = draft.title
                    draft.productUrl?.let { url = it }
                    if (draft.option.isNotBlank()) option = draft.option
                    draft.displayedPriceWon?.let { limit = it.toString() }
                    changed()
                }
                Text("이미지에 상품 주소가 없으면 쿠팡 공유 링크를 별도로 붙여 넣어 주세요. 선택한 표시가격은 아래 금액 한도에 복사되므로 최종 구매 조건을 확인해 주세요.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(title, { title = it; changed() }, label = { Text("상품명") }, placeholder = { Text("예: 생수 2L 6개입") }, singleLine = true, enabled = inputEnabled, modifier = Modifier.fillMaxWidth())
                Text("쿠팡 화면에 표시되는 실제 상품명을 입력해 주세요. 결제 전 시험에서도 이 상품명으로 확인합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(url, { url = it; changed() }, label = { Text("쿠팡 상품 URL") }, placeholder = { Text("https://www.coupang.com/…") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), minLines = 2, maxLines = 4, enabled = inputEnabled, modifier = Modifier.fillMaxWidth())
                Text("쿠팡의 상품 주소나 공유 링크를 붙여 넣어 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(option, { option = it; changed() }, label = { Text("구매 옵션") }, placeholder = { Text("예: 600ml, 2개") }, minLines = 1, maxLines = 3, enabled = inputEnabled, modifier = Modifier.fillMaxWidth().testTag("purchase-option"))
                Text("쿠팡에 보이는 옵션을 그대로 입력해 주세요. 옵션의 ‘2개’ 같은 구성과 아래 구매 수량은 별개입니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(limit, { limit = it.take(18); changed() }, label = { Text("금액 한도") }, suffix = { Text("원") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = inputEnabled, modifier = Modifier.fillMaxWidth().testTag("purchase-limit"))
                Text("배송비를 포함한 최종 금액의 한도입니다. 이미지의 표시가격과 다를 수 있으니 확인해 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(quantity, { quantity = it.take(4); changed() }, label = { Text("구매 수량") }, suffix = { Text("개") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = inputEnabled, modifier = Modifier.weight(1f))
                    OutlinedTextField(interval, { interval = it.take(4); changed() }, label = { Text("구매 주기") }, suffix = { Text("일") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = inputEnabled, modifier = Modifier.weight(1f))
                }
                Text("다음 구매일", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth(), enabled = inputEnabled) { Text(LocalDate.ofEpochDay(date).format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", Locale.KOREA))) }
                if (item != null) Text("일정을 수정하면 이전 알림은 만료됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("item-editor-error")) }
            }
        },
        confirmButton = {
            Button(enabled = inputEnabled, onClick = {
                val q = quantity.toIntOrNull(); val days = interval.toIntOrNull(); val maximum = limit.toLongOrNull()
                error = when {
                    title.trim().length !in 1..100 -> "상품명은 1~100자로 입력해 주세요"
                    option.trim().length > 120 || CartAssistPolicy.normalize(option).length < 2 -> "구매 옵션은 문자·숫자 2자 이상, 전체 120자 이내로 입력해 주세요"
                    maximum == null || maximum !in 1L..100_000_000L -> "금액 한도는 1원~1억원으로 입력해 주세요"
                    q == null || q !in 1..999 -> "수량은 1~999개로 입력해 주세요"
                    days == null || days !in 1..365 -> "주기는 1~365일로 입력해 주세요"
                    else -> null
                }
                if (error == null && q != null && days != null && maximum != null) {
                    try { onSave(title.trim(), CoupangUrl.normalize(url), q, days, date, option.trim(), maximum) }
                    catch (invalid: IllegalArgumentException) { error = invalid.message ?: "상품 주소를 확인해 주세요" }
                }
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("취소") } },
    )
    if (showDatePicker) {
        // Material DatePicker uses UTC midnight, not a timestamp in the device's time zone.
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date * 86_400_000L, yearRange = 2000..2100)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = { TextButton(onClick = { pickerState.selectedDateMillis?.let { date = Math.floorDiv(it, 86_400_000L); changed() }; showDatePicker = false }, enabled = pickerState.selectedDateMillis != null && inputEnabled) { Text("선택") } },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("취소") } },
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun EmptyState(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("↻", fontSize = 44.sp, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private fun formatDate(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dateFormat)
