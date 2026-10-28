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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import dev.local.rebuy.autobuy.AutoBuyController
import dev.local.rebuy.cart.CartAssistPolicy
import dev.local.rebuy.cart.CartAssistController
import dev.local.rebuy.cart.CartBatchController
import dev.local.rebuy.data.PurchaseRecord
import dev.local.rebuy.data.RoutineItem
import dev.local.rebuy.domain.CoupangUrl
import dev.local.rebuy.domain.ProductVariantCodec
import dev.local.rebuy.importproduct.ProductLinkImportController
import dev.local.rebuy.importproduct.ProductLinkImportState
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

private val dateFormat = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREA)
private val historyFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.KOREA)
@Composable
fun RebuyApp(
    viewModel: RoutineViewModel,
    completionRequest: NotificationCompletionRequest?,
    onCompletionRequestConsumed: () -> Unit,
    openProduct: (String) -> Boolean,
    incomingImage: IncomingProductImage? = null,
    onIncomingImageConsumed: () -> Unit = {},
) {
    RebuyTheme {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val repositoryBusy by viewModel.busy.collectAsStateWithLifecycle()
        val batchState by CartBatchController.state.collectAsStateWithLifecycle()
        var batchPreparing by remember { mutableStateOf(false) }
        val productImportState by ProductLinkImportController.state.collectAsStateWithLifecycle()
        var productImportOpen by remember { mutableStateOf(false) }
        val busy = repositoryBusy || batchState.running || batchPreparing || productImportOpen || productImportState is ProductLinkImportState.Running
        val completion by viewModel.completionPrompt.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(context) { CartBatchController.restore(context) }
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
        var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
        var showCartAssist by rememberSaveable { mutableStateOf(false) }
        var showCartBatch by rememberSaveable { mutableStateOf(false) }
        var cartAssistUrl by rememberSaveable { mutableStateOf("") }
        var cartAssistTitle by rememberSaveable { mutableStateOf("") }
        var cartAssistOption by rememberSaveable { mutableStateOf("") }
        var cartAssistQuantity by rememberSaveable { mutableIntStateOf(1) }
        var cartAssistLimit by rememberSaveable { mutableStateOf<Long?>(null) }
        var showAutoBuy by rememberSaveable { mutableStateOf(false) }
        var autoBuyItemId by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(incomingImage?.id, state.loading, state.loadError, busy, showCartBatch) {
            if (incomingImage != null && !state.loading && state.loadError == null && !busy && !showCartBatch) {
                if (showCartAssist) {
                    // Keep the cart URL and unsaved conditions while its image review opens.
                    CartAssistController.cancel()
                } else {
                    // Sharing into an open editor preserves its unsaved fields and selected item.
                    if (!showEditor) editorId = null
                    if (showAutoBuy) AutoBuyController.cancel()
                    showAutoBuy = false
                    deleteId = null
                    viewModel.dismissCompletion()
                    tab = 0
                    showEditor = true
                }
            }
        }
        val onAutoBuy: (RoutineItem?) -> Unit = { item ->
            if (!busy) {
                autoBuyItemId = item?.id
                showAutoBuy = true
            }
        }
        val onCartAssist: (RoutineItem?) -> Unit = { item ->
            if (!busy) {
                cartAssistUrl = item?.productUrl.orEmpty()
                cartAssistTitle = item?.title.orEmpty()
                cartAssistOption = item?.purchaseOption.orEmpty()
                cartAssistQuantity = item?.quantity ?: 1
                cartAssistLimit = item?.maxTotalWon
                showCartAssist = true
            }
        }
        val onCartBatch: () -> Unit = {
            if (batchState.running) showCartBatch = true
            else if (!busy) {
                if (CartAssistController.state.value is dev.local.rebuy.cart.CartAssistState.Running || AutoBuyController.isActive()) {
                    scope.launch { snackbar.showSnackbar("다른 구매 도우미가 실행 중입니다. 실행을 마친 뒤 다시 시작해 주세요.") }
                } else {
                    showEditor = false
                    showCartAssist = false
                    showAutoBuy = false
                    deleteId = null
                    viewModel.dismissCompletion()
                    showCartBatch = true
                }
            }
        }
        val onOpen: (String) -> Unit = { url ->
            if (!openProduct(url)) scope.launch { snackbar.showSnackbar("쿠팡 URL을 열 수 없습니다. 브라우저 설치와 상품 주소를 확인해 주세요") }
        }
        val dueCount = state.items.count { !it.archived && it.enabled && it.nextPurchaseEpochDay <= today }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RebuyIcon(RebuyIconKind.Basket, tint = MaterialTheme.colorScheme.primary)
                            Text("다시담기", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    },
                    actions = {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(50)) {
                            Text("내 기기 저장", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                        }
                        Spacer(Modifier.width(20.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf("구매 목록" to RebuyIconKind.Basket, "구매 로그" to RebuyIconKind.History, "설정" to RebuyIconKind.Settings).forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == index, onClick = { tab = index },
                            icon = {
                                if (index == 0 && !state.loading && state.loadError == null) {
                                    BadgedBox(badge = {
                                        Badge(Modifier.testTag("purchase-pending-count").semantics { contentDescription = "구매 대기 ${dueCount}개" }) { Text(dueCount.toString()) }
                                    }) { RebuyIcon(icon) }
                                } else RebuyIcon(icon)
                            }, label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
            },
            floatingActionButton = {
                if (tab == 0 && !state.loading && state.loadError == null && !busy) ExtendedFloatingActionButton(
                    onClick = { editorId = null; showEditor = true },
                    modifier = Modifier.testTag("add-product").semantics { contentDescription = "상품 등록" },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(20.dp),
                    icon = { RebuyIcon(RebuyIconKind.Plus) }, text = { Text("상품 등록") },
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
                        onDelete = { deleteId = it.id },
                        onOpen = onOpen, onComplete = viewModel::requestCompletion,
                        onCartAssist = { onCartAssist(it) },
                        onAutoBuy = { onAutoBuy(it) },
                        batchRunning = batchState.running, onCartBatch = onCartBatch,
                    )
                    tab == 1 -> PurchaseLogList(state.purchaseLogs)
                    else -> SettingsScreen(state.settings, notificationsAllowed, busy, requestPermission, ::openNotificationSettings, viewModel::updateSettings, { onAutoBuy(null) })
                }
            }
        }
        if (showEditor && !state.loading) ItemEditor(
            item = state.items.firstOrNull { it.id == editorId },
            today = today,
            busy = busy, onDismiss = { if (!busy) showEditor = false },
            incomingImage = incomingImage,
            onIncomingImageConsumed = onIncomingImageConsumed,
            onImportOpenChanged = { productImportOpen = it },
            onSave = { title, url, quantity, interval, date, option, limit, variants ->
                viewModel.saveItem(editorId, title, url, quantity, interval, date, option, limit, variants) { showEditor = false }
            },
        )
        if (showCartAssist) CartAssistDialog(
            initialUrl = cartAssistUrl,
            initialTitle = cartAssistTitle,
            initialOption = cartAssistOption,
            quantity = cartAssistQuantity,
            maxTotalWon = cartAssistLimit,
            incomingImage = incomingImage,
            onIncomingImageConsumed = onIncomingImageConsumed,
            onDismiss = { showCartAssist = false },
        )
        if (showCartBatch && !state.loading) CartBatchDialog(
            items = state.items,
            today = today,
            onPreparingChanged = { batchPreparing = it },
            onDismiss = { showCartBatch = false },
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
                        Text("쿠팡에서 직접 결제한 뒤 확인해 주세요. 구매 로그를 남기고 완료일을 기준으로 다음 구매일을 갱신합니다.")
                        Text("같은 예정 건은 한 번만 기록됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = { Button(onClick = { viewModel.completePurchase(prompt) }, enabled = !busy) { Text("구매 완료 기록") } },
                dismissButton = { TextButton(onClick = viewModel::dismissCompletion, enabled = !busy) { Text("아직이에요") } },
            )
        }
        state.items.firstOrNull { it.id == deleteId }?.let { item ->
            AlertDialog(
                onDismissRequest = { if (!busy) deleteId = null },
                title = { Text("상품을 삭제할까요?") },
                text = { Text("${item.title}의 등록 정보와 알림을 삭제합니다. 이 작업은 되돌릴 수 없습니다. 기존 구매 로그는 별도로 유지되며 쿠팡 장바구니는 변경하지 않습니다.") },
                confirmButton = { TextButton(onClick = { viewModel.deleteItem(item) { deleteId = null } }, enabled = !busy, modifier = Modifier.testTag("delete-product-confirm")) { Text("삭제", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { deleteId = null }, enabled = !busy) { Text("취소") } },
            )
        }
    }
}

@Composable
private fun RoutineList(
    source: List<RoutineItem>, today: Long, notificationsAllowed: Boolean, remindersEnabled: Boolean, busy: Boolean,
    requestPermission: () -> Unit, onEdit: (RoutineItem) -> Unit, onToggle: (RoutineItem) -> Unit,
    onDelete: (RoutineItem) -> Unit, onOpen: (String) -> Unit, onComplete: (RoutineItem) -> Unit,
    onCartAssist: (RoutineItem) -> Unit,
    onAutoBuy: (RoutineItem) -> Unit,
    batchRunning: Boolean, onCartBatch: () -> Unit,
) {
    val all = source.filterNot { it.archived }
    val dueCount = all.count { it.enabled && it.nextPurchaseEpochDay <= today }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val visible = all.filter { when (filter) { 1 -> it.enabled && it.nextPurchaseEpochDay <= today; 2 -> !it.enabled; else -> true } }
        .sortedWith(compareBy<RoutineItem> { !it.enabled }.thenBy { it.nextPurchaseEpochDay }.thenBy { it.title })
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("routine-list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("나의 구매 루틴", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("필요한 날, 다시 담아요", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("자주 사는 상품을 한곳에서 챙기세요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Button(
                onClick = onCartBatch,
                enabled = batchRunning || (!busy && all.any { it.enabled }),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("cart-batch-open"),
                shape = RoundedCornerShape(14.dp),
            ) {
                RebuyIcon(RebuyIconKind.Basket, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (batchRunning) "담기 진행 보기" else "예정 상품 담기 (${dueCount}개)", modifier = Modifier.weight(1f))
                RebuyIcon(RebuyIconKind.ArrowRight, Modifier.size(18.dp))
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
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("전체 ${all.size}", "예정 $dueCount", "일시정지 ${all.count { !it.enabled }}").forEachIndexed { index, text ->
                    FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(text) })
                }
            }
        }
        if (visible.isEmpty()) item {
            EmptyState(if (all.isEmpty()) "첫 상품을 등록해 보세요" else "이 목록은 아직 비어 있어요", "쿠팡의 상품 또는 공유 URL을 붙여 넣고\n수량과 구매 주기를 정해 주세요.", Modifier.padding(top = 44.dp))
        }
        items(visible, key = { it.id }) { item ->
            RoutineCard(item, today, busy, onEdit, onToggle, onDelete, onOpen, onComplete, onCartAssist, onAutoBuy)
        }
        if (visible.isNotEmpty()) item {
            Text("장바구니에서 주문한 뒤 구매 완료를 기록해 주세요. 결제는 직접 확인합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun RoutineCard(
    item: RoutineItem, today: Long, busy: Boolean,
    onEdit: (RoutineItem) -> Unit, onToggle: (RoutineItem) -> Unit, onDelete: (RoutineItem) -> Unit,
    onOpen: (String) -> Unit, onComplete: (RoutineItem) -> Unit,
    onCartAssist: (RoutineItem) -> Unit,
    onAutoBuy: (RoutineItem) -> Unit,
) {
    val isDue = item.enabled && item.nextPurchaseEpochDay <= today
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("routine-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp)) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        RebuyIcon(RebuyIconKind.Basket, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = if (isDue) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (isDue) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            when { !item.enabled -> "일시정지"; item.nextPurchaseEpochDay < today -> "예정일 지남"; isDue -> "오늘 구매 예정"; else -> "${item.nextPurchaseEpochDay - today}일 뒤" },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.size(48.dp).testTag("product-more").semantics { contentDescription = "${item.title} 관리" }) {
                        RebuyIcon(RebuyIconKind.More, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menu && !busy, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("쿠팡 열기") }, onClick = { menu = false; onOpen(item.productUrl) }, modifier = Modifier.testTag("product-open"))
                        DropdownMenuItem(text = { Text("장바구니 담기") }, onClick = { menu = false; onCartAssist(item) }, enabled = item.quantity == 1, modifier = Modifier.testTag("cart-open"))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DropdownMenuItem(text = { Text("상품 수정") }, onClick = { menu = false; onEdit(item) })
                        DropdownMenuItem(text = { Text("결제 전 시험") }, onClick = { menu = false; onAutoBuy(item) })
                        DropdownMenuItem(text = { Text(if (item.enabled) "알림 일시정지" else "알림 다시 시작") }, onClick = { menu = false; onToggle(item) })
                        DropdownMenuItem(text = { Text("상품 삭제", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete(item) }, modifier = Modifier.testTag("delete-product"))
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("product-purchase-info"),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("옵션 · ${item.purchaseOption?.takeIf { it.isNotBlank() } ?: "링크에 지정된 옵션"}", style = MaterialTheme.typography.bodyMedium)
                    Text("다음 구매 ${formatDate(item.nextPurchaseEpochDay)} · ${item.intervalDays}일마다", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.quantity != 1) Text("구매 수량 ${item.quantity}개", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    item.maxTotalWon?.let { maximum ->
                        Text("금액 한도 ${NumberFormat.getIntegerInstance(Locale.KOREA).format(maximum)}원", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = { onComplete(item) }, enabled = item.enabled && !busy,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.align(Alignment.End).testTag("complete-purchase"),
                    ) { Text("구매 완료 기록", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

@Composable
private fun PurchaseLogList(logs: List<PurchaseRecord>) {
    if (logs.isEmpty()) {
        EmptyState("구매 로그가 쌓이는 곳", "구매 완료를 확인하면 당시 상품과 옵션,\n수량과 완료 시간이 기록됩니다.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("purchase-log-list"),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("구매 로그", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("직접 완료 확인한 ${logs.size}건 · 상품 삭제 후에도 유지됩니다.", modifier = Modifier.padding(top = 8.dp, bottom = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(logs.sortedByDescending { it.completedAtEpochMillis }, key = { it.id }) { record ->
            Column(Modifier.fillMaxWidth().testTag("purchase-log-entry"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RebuyIcon(RebuyIconKind.Check, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(Instant.ofEpochMilli(record.completedAtEpochMillis).atZone(ZoneId.systemDefault()).format(historyFormat), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(record.title, style = MaterialTheme.typography.titleMedium)
                record.purchaseOption?.let { Text("옵션 · $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("${record.quantity}개 · 예정일 ${formatDate(record.scheduledEpochDay)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item { Text("사용자가 구매 완료를 확인한 시점의 정보를 기록합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp)) }
    }
}

@Composable
private fun SettingsScreen(
    settings: ReminderSettings, notificationsAllowed: Boolean, busy: Boolean,
    requestPermission: () -> Unit, openSystemSettings: () -> Unit,
    onSave: (Boolean, Int, Int) -> Unit,
    onAutoBuy: () -> Unit,
) {
    var enabled by rememberSaveable(settings.notificationsEnabled) { mutableStateOf(settings.notificationsEnabled) }
    var hour by rememberSaveable(settings.hour) { mutableStateOf(settings.hour.toString().padStart(2, '0')) }
    var minute by rememberSaveable(settings.minute) { mutableStateOf(settings.minute.toString().padStart(2, '0')) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("구매일을 챙기는 방법", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("알림과 기기 설정을 한곳에서 관리하세요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RebuyIcon(RebuyIconKind.Bell, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("예정일 알림", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("등록한 모든 상품에 적용", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("알림 시간", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = hour, onValueChange = { hour = it.take(2); error = null }, label = { Text("시 (0~23)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                    Text(":", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = minute, onValueChange = { minute = it.take(2); error = null }, label = { Text("분 (0~59)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = {
                    val h = hour.toIntOrNull(); val m = minute.toIntOrNull()
                    if (h == null || h !in 0..23 || m == null || m !in 0..59) error = "올바른 시와 분을 입력해 주세요"
                    else onSave(enabled, h, m)
                }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("알림 설정 저장") }
                Text("기기의 현재 시간대를 따릅니다. 절전 모드나 기기 상태에 따라 알림이 늦어질 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RebuyIcon(RebuyIconKind.Settings, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("기기 알림 ${if (notificationsAllowed) "허용됨" else "꺼짐"}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Text(if (notificationsAllowed) "알림이 오지 않으면 기기 설정에서 알림 채널도 확인해 주세요." else "예정일 알림을 받으려면 기기에서도 알림을 허용해 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = if (notificationsAllowed) openSystemSettings else requestPermission, contentPadding = PaddingValues(0.dp)) {
                    Text(if (notificationsAllowed) "기기 알림 설정 열기" else "알림 허용 / 설정")
                    Spacer(Modifier.width(6.dp))
                    RebuyIcon(RebuyIconKind.ArrowRight, Modifier.size(16.dp))
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("구매 조건 · 결제 전 시험", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("실제 결제는 비활성화되어 있습니다. 상품·옵션·금액 한도를 저장하고 직접 시작한 시험에서 결제 전 단계까지만 확인합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onAutoBuy, enabled = !busy, contentPadding = PaddingValues(0.dp)) {
                    Text("결제 전 시험 설정")
                    Spacer(Modifier.width(6.dp))
                    RebuyIcon(RebuyIconKind.ArrowRight, Modifier.size(16.dp))
                }
            }
        }
        Column(Modifier.padding(top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("나만의 로컬 구매 메모", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text("상품 설정과 구매 로그는 별도로 이 기기에 저장됩니다. 서버와 로그인 없이 사용하며, 앱을 삭제하면 저장한 정보도 삭제됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("쿠팡 앱의 상품 화면을 확인해 장바구니에 담습니다. 주문·결제와 구매 완료 기록은 직접 확인해 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ItemEditor(
    item: RoutineItem?, today: Long, busy: Boolean, onDismiss: () -> Unit,
    incomingImage: IncomingProductImage? = null,
    onIncomingImageConsumed: () -> Unit = {},
    onImportOpenChanged: (Boolean) -> Unit = {},
    onSave: (String, String, Int, Int, Long, String, Long?, String?) -> Unit,
) {
    val key = item?.id ?: "new"
    var title by rememberSaveable(key) { mutableStateOf(item?.title.orEmpty()) }
    var url by rememberSaveable(key) { mutableStateOf(item?.productUrl.orEmpty()) }
    var option by rememberSaveable(key) { mutableStateOf(item?.purchaseOption.orEmpty()) }
    var variantsJson by rememberSaveable(key) { mutableStateOf(item?.productVariantsJson) }
    var showUrlImport by rememberSaveable(key) { mutableStateOf(false) }
    var showManualDetails by rememberSaveable(key) { mutableStateOf(false) }
    var limit by rememberSaveable(key) { mutableStateOf(item?.maxTotalWon?.toString().orEmpty()) }
    var autoPriceSourceUrl by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var quantity by rememberSaveable(key) { mutableStateOf((item?.quantity ?: 1).toString()) }
    var interval by rememberSaveable(key) { mutableStateOf((item?.intervalDays ?: 30).toString()) }
    var date by rememberSaveable(key) { mutableLongStateOf(item?.nextPurchaseEpochDay ?: today) }
    var showDatePicker by rememberSaveable(key) { mutableStateOf(false) }
    var error by rememberSaveable(key) { mutableStateOf<String?>(null) }
    val inputEnabled = !busy
    fun changed() { error = null }
    fun identityChanged() {
        variantsJson = "[]"
        if (autoPriceSourceUrl != null) { limit = ""; autoPriceSourceUrl = null }
        changed()
    }

    LaunchedEffect(incomingImage?.id) { if (incomingImage != null) showManualDetails = true }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                RebuyIcon(RebuyIconKind.Link, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text(if (item == null) "다시 살 상품 등록" else "상품 수정", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("선택한 옵션의 쿠팡 링크를 붙여 넣어 주세요.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    url, { url = it; title = ""; option = ""; identityChanged() },
                    label = { Text("쿠팡 상품 URL") }, placeholder = { Text("https://link.coupang.com/…") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), minLines = 2, maxLines = 4,
                    shape = RoundedCornerShape(14.dp),
                    enabled = inputEnabled, modifier = Modifier.fillMaxWidth().testTag("product-url"),
                )
                Button(onClick = {
                    if (runCatching { CoupangUrl.normalize(url) }.isFailure) error = "쿠팡 상품 링크를 먼저 입력해 주세요."
                    else showUrlImport = true
                }, enabled = inputEnabled && url.isNotBlank(), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("product-url-import-open")) {
                    Text(if (title.isBlank()) "링크에서 상품 불러오기" else "상품 정보 다시 불러오기")
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("선택한 상품", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(title.ifBlank { "링크에서 상품 정보를 불러와 주세요." }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        if (option.isNotBlank()) Text("옵션 · $option", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Text("옵션은 링크에서 함께 확인합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!showManualDetails) TextButton(
                    onClick = { showManualDetails = true }, enabled = inputEnabled,
                    modifier = Modifier.testTag("product-details-edit"), contentPadding = PaddingValues(0.dp),
                ) { Text("상품 정보 직접 보완") }
                if (showManualDetails) {
                    Text("읽지 못한 정보만 보완해 주세요. 상품을 열려면 링크가 필요합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(title, { title = it; identityChanged() }, label = { Text("상품명") }, singleLine = true, enabled = inputEnabled, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(option, { option = it; identityChanged() }, label = { Text("구매 옵션") }, placeholder = { Text("예: 600ml, 2개") }, minLines = 1, maxLines = 3, enabled = inputEnabled, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("purchase-option"))
                    ProductImageImport(
                        enabled = inputEnabled,
                        incomingImage = incomingImage,
                        onIncomingImageConsumed = onIncomingImageConsumed,
                    ) { draft ->
                        val previous = Triple(title, url, option)
                        if (draft.title.isNotBlank()) title = draft.title
                        draft.productUrl?.let { url = it }
                        if (draft.option.isNotBlank()) option = draft.option
                        draft.displayedPriceWon?.let { limit = it.toString(); autoPriceSourceUrl = null }
                        if (previous != Triple(title, url, option)) identityChanged() else changed()
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RebuyIcon(RebuyIconKind.Calendar, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("구매 일정", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(quantity, { quantity = it.take(4); changed() }, label = { Text("구매 수량") }, suffix = { Text("개") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = inputEnabled, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                    OutlinedTextField(interval, { interval = it.take(4); changed() }, label = { Text("구매 주기") }, suffix = { Text("일") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = inputEnabled, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("다음 구매일", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp), enabled = inputEnabled) { Text(LocalDate.ofEpochDay(date).format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", Locale.KOREA))) }
                    if (item != null) Text("일정을 수정하면 이전 알림은 만료됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(limit, { limit = it.take(18); autoPriceSourceUrl = null; changed() }, label = { Text("금액 한도 (선택)") }, suffix = { Text("원") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = inputEnabled, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("purchase-limit"))
                    Text("링크의 상품 정보를 적용하면 비어 있는 한도에 읽은 상품가를 채웁니다. 이미 입력한 한도는 유지하며 직접 수정할 수 있습니다. 담기 전 비교는 배송비·최종 결제 금액을 포함하지 않습니다. 비워 두면 가격 검사를 생략합니다. 결제 전 시험에는 배송비 포함 한도가 필요합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("item-editor-error")) }
            }
        },
        confirmButton = {
            Button(enabled = inputEnabled, onClick = {
                val q = quantity.toIntOrNull(); val days = interval.toIntOrNull(); val maximum = limit.toLongOrNull()
                error = when {
                    runCatching { CoupangUrl.normalize(url) }.isFailure -> "쿠팡 상품 링크를 먼저 입력해 주세요"
                    title.isBlank() || option.isBlank() -> "링크에서 상품 정보를 불러온 뒤 저장해 주세요"
                    title.trim().length !in 1..100 -> "상품명은 1~100자로 입력해 주세요"
                    option.trim().length > 120 || CartAssistPolicy.normalize(option).length < 2 -> "구매 옵션은 문자·숫자 2자 이상, 전체 120자 이내로 입력해 주세요"
                    limit.isNotBlank() && (maximum == null || maximum !in 1L..100_000_000L) -> "금액 한도는 1원~1억원으로 입력해 주세요"
                    q == null || q !in 1..999 -> "수량은 1~999개로 입력해 주세요"
                    days == null || days !in 1..365 -> "주기는 1~365일로 입력해 주세요"
                    else -> null
                }
                if (error == null && q != null && days != null) {
                    try { onSave(title.trim(), CoupangUrl.normalize(url), q, days, date, option.trim(), maximum, variantsJson) }
                    catch (invalid: IllegalArgumentException) { error = invalid.message ?: "상품 주소를 확인해 주세요" }
                }
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("취소") } },
    )
    if (showUrlImport) ProductUrlImportDialog(
        rawInitialUrl = url,
        onApply = { variant, displayedPriceWon ->
            variantsJson = ProductVariantCodec.encode(listOf(variant))
            if (autoPriceSourceUrl != null &&
                (autoPriceSourceUrl != variant.productUrl || title != variant.title || option != variant.option)) {
                limit = ""
                autoPriceSourceUrl = null
            }
            title = variant.title
            url = variant.productUrl
            option = variant.option
            if (limit.isBlank() && displayedPriceWon != null && displayedPriceWon in 1L..100_000_000L) {
                limit = displayedPriceWon.toString()
                autoPriceSourceUrl = variant.productUrl
            }
            changed()
            showUrlImport = false
        },
        onDismiss = { showUrlImport = false },
        onOpenChanged = onImportOpenChanged,
        autoStart = true,
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
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) {
            Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                RebuyIcon(RebuyIconKind.Basket, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private fun formatDate(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dateFormat)
