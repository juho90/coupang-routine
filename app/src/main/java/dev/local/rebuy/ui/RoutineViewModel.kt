package dev.local.rebuy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.local.rebuy.data.PurchaseRecord
import dev.local.rebuy.data.RoutineItem
import dev.local.rebuy.data.RoutineRepository
import dev.local.rebuy.domain.CompletionResult
import dev.local.rebuy.domain.CoupangCartProductLink
import dev.local.rebuy.domain.ProductVariant
import dev.local.rebuy.domain.ProductVariantCodec
import dev.local.rebuy.cart.CartAssistPolicy
import dev.local.rebuy.reminders.ReminderScheduler
import dev.local.rebuy.settings.ReminderSettings
import dev.local.rebuy.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RoutineUiState(
    val items: List<RoutineItem> = emptyList(),
    val history: List<PurchaseRecord> = emptyList(),
    val settings: ReminderSettings = ReminderSettings(),
    val loading: Boolean = true,
    val loadError: String? = null,
)

data class CompletionPrompt(
    val itemId: String,
    val expectedCycleId: String,
    val title: String,
    val quantity: Int,
    val scheduledEpochDay: Long,
)

/** All completion entry points carry a snapshot of the cycle, never a newly looked-up cycle. */
class RoutineViewModel(
    private val repository: RoutineRepository,
    private val scheduler: ReminderScheduler,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _completionPrompt = MutableStateFlow<CompletionPrompt?>(null)
    val completionPrompt: StateFlow<CompletionPrompt?> = _completionPrompt.asStateFlow()

    val state = combine(repository.items, repository.history, settingsRepository.settings) { items, history, settings ->
        RoutineUiState(items = items, history = history, settings = settings, loading = false)
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(RoutineUiState(loading = false, loadError = "저장된 정보를 불러오지 못했습니다. 앱을 다시 열어 주세요."))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutineUiState())

    fun saveItem(
        id: String?, title: String, productUrl: String, quantity: Int,
        intervalDays: Int, nextPurchaseEpochDay: Long,
        purchaseOption: String, maxTotalWon: Long?, productVariantsJson: String? = null, onSaved: () -> Unit,
    ) = mutate {
        repository.saveItem(id, title, productUrl, quantity, intervalDays, nextPurchaseEpochDay, purchaseOption,
            maxTotalWon, productVariantsJson, clearMaxTotalWon = maxTotalWon == null)
        onSaved()
        reconcileAfterChange("상품을 저장했습니다")
    }

    fun selectVariant(item: RoutineItem, variant: ProductVariant) = mutate {
        repository.selectVariant(item.id, variant.productUrl)
        scheduler.cancelNotification(item.id)
        reconcileAfterChange("선택 옵션을 저장했습니다. 다음 담기에 이 옵션을 사용합니다")
    }

    fun addVariant(item: RoutineItem, variant: ProductVariant) = mutate {
        val current = requireNotNull(repository.getItem(item.id)) { "등록된 상품을 찾을 수 없어요." }
        require(!current.archived) { "보관된 상품은 수정할 수 없어요." }
        val imported = ProductVariantCodec.validate(listOf(variant)).single()
        val importedLink = CoupangCartProductLink.parse(imported.productUrl)
        val currentLink = runCatching { CoupangCartProductLink.parse(current.productUrl) }.getOrNull()
        if (currentLink == null) {
            require(CartAssistPolicy.normalize(current.title) == CartAssistPolicy.normalize(imported.title) &&
                CartAssistPolicy.normalizeOption(current.purchaseOption.orEmpty()) == CartAssistPolicy.normalizeOption(imported.option)) {
                "먼저 상품 수정에서 현재 URL의 정보를 다시 읽고 저장한 뒤 다른 옵션을 추가해 주세요."
            }
        } else require(currentLink.productId == importedLink.productId) { "같은 상품의 옵션 URL만 추가할 수 있습니다." }
        val selected = currentLink?.let { link ->
            current.purchaseOption?.let { ProductVariant(current.title, it, link.canonicalUrl) }
        }
        val previous = (ProductVariantCodec.decode(current.productVariantsJson) + listOfNotNull(selected))
            .distinctBy { it.productUrl }
        val variants = previous.filterNot { it.productUrl == imported.productUrl } + imported
        repository.saveItem(current.id, imported.title, imported.productUrl, current.quantity, current.intervalDays,
            current.nextPurchaseEpochDay, imported.option, current.maxTotalWon, ProductVariantCodec.encode(variants))
        scheduler.cancelNotification(current.id)
        reconcileAfterChange("옵션을 추가하고 선택했습니다. 다음 담기에 이 옵션을 사용합니다")
    }

    fun setEnabled(item: RoutineItem, enabled: Boolean) = mutate {
        repository.setEnabled(item.id, enabled)
        scheduler.cancelNotification(item.id)
        reconcileAfterChange(if (enabled) "구매 알림을 다시 시작했습니다" else "구매 알림을 잠시 멈췄습니다")
    }

    fun archive(item: RoutineItem) = mutate {
        repository.archiveItem(item.id)
        scheduler.cancelNotification(item.id)
        reconcileAfterChange("목록에서 보관했습니다. 구매 이력은 유지됩니다")
    }

    fun requestCompletion(item: RoutineItem) {
        if (!item.enabled || item.archived) return
        _completionPrompt.value = item.asCompletionPrompt(item.cycleId)
    }

    /** Used by a notification action. A stale notification must not complete today's new cycle. */
    fun requestCompletion(itemId: String, expectedCycleId: String) {
        viewModelScope.launch {
            try {
                val item = repository.getItem(itemId)
                if (item == null || item.archived || !item.enabled || item.cycleId != expectedCycleId) {
                    messages.send("이미 처리되었거나 변경된 알림입니다. 현재 구매 목록을 확인해 주세요.")
                } else {
                    _completionPrompt.value = item.asCompletionPrompt(expectedCycleId)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                messages.send("알림에 연결된 상품을 불러오지 못했습니다")
            }
        }
    }

    fun dismissCompletion() { _completionPrompt.value = null }

    fun completePurchase(prompt: CompletionPrompt) = mutate {
        val result = repository.completePurchase(prompt.itemId, prompt.expectedCycleId)
        _completionPrompt.value = null
        // A replayed dialog can belong to an older cycle. Its notification may already have
        // been replaced by the current cycle, so only a successful completion may cancel it.
        if (result == CompletionResult.Completed) scheduler.cancelNotification(prompt.itemId)
        val message = when (result) {
            CompletionResult.Completed -> "구매를 기록하고 다음 구매일을 갱신했습니다"
            CompletionResult.AlreadyRecorded -> "이미 기록한 구매입니다. 중복 기록하지 않았습니다"
            CompletionResult.Stale -> "상품의 일정이 변경되었습니다. 현재 구매 목록을 확인해 주세요"
            CompletionResult.NotFound -> "상품을 찾을 수 없습니다"
        }
        reconcileAfterChange(message)
    }

    fun updateSettings(enabled: Boolean, hour: Int, minute: Int) = mutate {
        settingsRepository.update(enabled, hour, minute)
        reconcileAfterChange("알림 설정을 저장했습니다")
    }

    fun notificationPermissionChanged() {
        viewModelScope.launch {
            try { scheduler.reconcileAll() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                messages.send("알림 예약을 갱신하지 못했습니다. 앱을 다시 열어 주세요")
            }
        }
    }

    private fun RoutineItem.asCompletionPrompt(cycle: String) =
        CompletionPrompt(id, cycle, title, quantity, nextPurchaseEpochDay)

    private fun mutate(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { block() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                messages.send(error.message?.takeIf { error is IllegalArgumentException }
                    ?: "변경 사항을 저장하지 못했습니다. 다시 시도해 주세요")
            } finally { _busy.value = false }
        }
    }

    private suspend fun reconcileAfterChange(successMessage: String) {
        try {
            scheduler.reconcileAll()
            messages.send(successMessage)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            messages.send("변경 사항은 저장했습니다. 알림 예약을 갱신하려면 앱을 다시 열어 주세요")
        }
    }
}
