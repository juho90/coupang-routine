package dev.local.rebuy.autobuy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.local.rebuy.data.RoutineItem
import dev.local.rebuy.domain.CoupangUrl
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.autoBuyDataStore by preferencesDataStore(name = "auto_buy_conditions")

/** Condition snapshots and attempt gates share one atomic, durable Preferences transaction. */
class AutoBuyStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(context.applicationContext.autoBuyDataStore)

    val profiles: Flow<List<AutoBuyProfile>> = dataStore.data.map(::readProfiles).distinctUntilChanged()
    val attempts: Flow<List<AutoBuyAttempt>> = dataStore.data.map(::readAttempts).distinctUntilChanged()

    suspend fun getProfile(itemId: String): AutoBuyProfile? = profiles.first().find { it.itemId == itemId }
    suspend fun getAttempt(attemptId: String): AutoBuyAttempt? = attempts.first().find { it.id == attemptId }

    suspend fun saveProfile(profile: AutoBuyProfile): AutoBuyProfile {
        require(!profile.enabled) { AutoBuyPolicy.PURCHASE_BLOCKED_MESSAGE }
        require(profile.itemId.isNotBlank()) { "상품을 먼저 등록해 주세요." }
        require(profile.expectedTitle.trim().length in 1..150) { "화면에 표시되는 상품명은 1~150자로 입력해 주세요." }
        require(profile.expectedOption.trim().length in 1..150) { "화면에 표시되는 옵션은 1~150자로 입력해 주세요." }
        require(profile.maxTotalWon in 1..100_000_000) { "총 결제 한도는 1원~1억원으로 입력해 주세요." }
        require(profile.quantitySnapshot == 1) { "현재 주문 조건 미리보기는 수량 1개만 지원합니다." }
        val saved = profile.copy(
            expectedTitle = profile.expectedTitle.trim(),
            expectedOption = profile.expectedOption.trim(),
            productUrlSnapshot = CoupangUrl.normalize(profile.productUrlSnapshot),
            enabled = false,
            revisionId = UUID.randomUUID().toString(),
        )
        dataStore.edit { preferences ->
            val updated = readProfiles(preferences).filterNot { it.itemId == saved.itemId } + saved
            preferences[PROFILES] = encodeProfiles(updated)
        }
        return saved
    }

    suspend fun setEnabled(itemId: String, enabled: Boolean) {
        require(!enabled) { AutoBuyPolicy.PURCHASE_BLOCKED_MESSAGE }
        dataStore.edit { preferences ->
            preferences[PROFILES] = encodeProfiles(readProfiles(preferences).map { profile ->
                if (profile.itemId == itemId) profile.copy(enabled = false, revisionId = UUID.randomUUID().toString())
                else profile
            })
        }
    }

    /** Actual execution is rejected before even creating an executable claim. */
    suspend fun claimAttempt(item: RoutineItem, profile: AutoBuyProfile, dryRun: Boolean): AutoBuyAttempt? {
        if (!dryRun) return null
        profile.requireMatches(item)
        var claimed: AutoBuyAttempt? = null
        dataStore.edit { preferences ->
            val stored = readProfiles(preferences).find { it.itemId == item.id }
            if (stored == null || stored.revisionId != profile.revisionId || stored != profile) return@edit
            stored.requireMatches(item)
            val ledger = readAttempts(preferences)
            val cycleAttempts = ledger.filter { it.itemId == item.id && it.cycleId == item.cycleId && it.dryRun == dryRun }
            // A claim interrupted by process death is not silently replayed. Finished previews can
            // be started again by another explicit user action; they never authorize payment.
            if (cycleAttempts.any { it.status in setOf(
                    AutoBuyStatus.CLAIMED, AutoBuyStatus.PREPARING, AutoBuyStatus.PAYMENT_SUBMITTED,
                    AutoBuyStatus.CONFIRMED, AutoBuyStatus.UNCERTAIN,
                ) }) return@edit
            val timestamp = now()
            val attempt = AutoBuyAttempt(
                itemId = item.id,
                cycleId = item.cycleId,
                profileRevisionId = profile.revisionId,
                dryRun = true,
                createdAtEpochMillis = timestamp,
                updatedAtEpochMillis = timestamp,
            )
            preferences[ATTEMPTS] = encodeAttempts(ledger + attempt)
            claimed = attempt
        }
        return claimed
    }

    /** Called before entering checkout; a second caller cannot repeat that external action. */
    suspend fun markExternalMutationStarted(attemptId: String): Boolean {
        var accepted = false
        dataStore.edit { preferences ->
            val ledger = readAttempts(preferences)
            val attempt = ledger.find { it.id == attemptId } ?: return@edit
            val profile = readProfiles(preferences).find { it.itemId == attempt.itemId } ?: return@edit
            if (!attempt.dryRun || attempt.status != AutoBuyStatus.CLAIMED ||
                profile.revisionId != attempt.profileRevisionId || attempt.externalMutationAtEpochMillis != null
            ) return@edit
            val timestamp = now()
            val updated = attempt.copy(
                status = AutoBuyStatus.PREPARING,
                message = "결제 전 주문 조건을 확인하고 있습니다. 실제 결제는 차단돼 있습니다.",
                externalMutationAtEpochMillis = timestamp,
                updatedAtEpochMillis = timestamp,
            )
            preferences[ATTEMPTS] = encodeAttempts(ledger.map { if (it.id == attemptId) updated else it })
            accepted = true
        }
        return accepted
    }

    /** Hard gate: no code path in this build can obtain authorization to click payment. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun markPaymentSubmitted(attemptId: String): Boolean = false

    suspend fun finishAttempt(attemptId: String, outcome: AutoBuyOutcome) {
        dataStore.edit { preferences ->
            val ledger = readAttempts(preferences)
            val previous = ledger.find { it.id == attemptId } ?: return@edit
            if (previous.status !in setOf(AutoBuyStatus.CLAIMED, AutoBuyStatus.PREPARING)) return@edit
            val status: AutoBuyStatus
            val message: String
            var total: Long? = null
            when (outcome) {
                is AutoBuyOutcome.DryRunReady -> {
                    val profile = readProfiles(preferences).find { it.itemId == previous.itemId }
                    if (previous.dryRun && profile != null &&
                        outcome.totalWon in 1..profile.maxTotalWon && profile.revisionId == previous.profileRevisionId
                    ) {
                        status = AutoBuyStatus.DRY_RUN_READY
                        message = "결제 전 조건을 확인했습니다. 실제 주문·결제는 실행하지 않았습니다."
                        total = outcome.totalWon
                    } else {
                        status = AutoBuyStatus.FAILED
                        message = "설정된 금액 한도 또는 상품 조건을 확인하지 못했습니다. 결제하지 않았습니다."
                    }
                }
                is AutoBuyOutcome.Blocked -> {
                    status = AutoBuyStatus.BLOCKED
                    message = outcome.reason
                }
                is AutoBuyOutcome.Failed -> {
                    status = AutoBuyStatus.FAILED
                    message = outcome.reason
                }
                is AutoBuyOutcome.Uncertain -> {
                    status = AutoBuyStatus.UNCERTAIN
                    message = outcome.reason
                }
                is AutoBuyOutcome.Confirmed -> {
                    // A runtime defect must not create a false purchase history entry. Actual
                    // orders are disabled, so a claimed confirmation requires manual inspection.
                    status = AutoBuyStatus.UNCERTAIN
                    message = "허용되지 않은 주문 완료 응답입니다. 쿠팡 주문 내역을 직접 확인해 주세요."
                }
            }
            val updated = previous.copy(status = status, message = message, totalWon = total, updatedAtEpochMillis = now())
            preferences[ATTEMPTS] = encodeAttempts(ledger.map { if (it.id == attemptId) updated else it })
        }
    }

    /** Diagnostic only; called by a stale scheduled worker without opening another app. */
    suspend fun recordBlocked(item: RoutineItem, profile: AutoBuyProfile, reason: String) {
        dataStore.edit { preferences ->
            val ledger = readAttempts(preferences)
            val prior = ledger.lastOrNull { it.itemId == item.id && it.cycleId == item.cycleId && !it.dryRun }
            if (prior != null && prior.status != AutoBuyStatus.BLOCKED) return@edit
            val timestamp = now()
            val blocked = prior?.copy(message = reason, updatedAtEpochMillis = timestamp)
                ?: AutoBuyAttempt(
                    itemId = item.id, cycleId = item.cycleId, profileRevisionId = profile.revisionId,
                    dryRun = false, status = AutoBuyStatus.BLOCKED, message = reason,
                    createdAtEpochMillis = timestamp, updatedAtEpochMillis = timestamp,
                )
            preferences[ATTEMPTS] = encodeAttempts(ledger.filterNot { it.id == blocked.id } + blocked)
        }
    }

    private companion object {
        val PROFILES = stringPreferencesKey("profiles_v1")
        val ATTEMPTS = stringPreferencesKey("attempts_v1")

        // Do not replace corrupt data with an empty ledger: that could authorize a replay.
        fun readProfiles(preferences: Preferences): List<AutoBuyProfile> {
            val array = JSONArray(preferences[PROFILES] ?: "[]")
            return (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                AutoBuyProfile(
                    itemId = row.getString("itemId"), expectedTitle = row.getString("expectedTitle"),
                    expectedOption = row.getString("expectedOption"), maxTotalWon = row.getLong("maxTotalWon"),
                    productUrlSnapshot = row.getString("productUrlSnapshot"), quantitySnapshot = row.getInt("quantitySnapshot"),
                    enabled = false, revisionId = row.getString("revisionId"),
                )
            }
        }

        fun readAttempts(preferences: Preferences): List<AutoBuyAttempt> {
            val array = JSONArray(preferences[ATTEMPTS] ?: "[]")
            return (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                AutoBuyAttempt(
                    id = row.getString("id"), itemId = row.getString("itemId"), cycleId = row.getString("cycleId"),
                    profileRevisionId = row.getString("profileRevisionId"), dryRun = row.getBoolean("dryRun"),
                    status = AutoBuyStatus.valueOf(row.getString("status")), message = row.getString("message"),
                    orderId = row.nullableString("orderId"), totalWon = row.nullableLong("totalWon"),
                    externalMutationAtEpochMillis = row.nullableLong("externalMutationAtEpochMillis"),
                    paymentSubmittedAtEpochMillis = row.nullableLong("paymentSubmittedAtEpochMillis"),
                    createdAtEpochMillis = row.getLong("createdAtEpochMillis"),
                    updatedAtEpochMillis = row.getLong("updatedAtEpochMillis"),
                )
            }
        }

        fun encodeProfiles(profiles: List<AutoBuyProfile>): String = JSONArray().apply {
            profiles.forEach { profile -> put(JSONObject().apply {
                put("itemId", profile.itemId); put("expectedTitle", profile.expectedTitle)
                put("expectedOption", profile.expectedOption); put("maxTotalWon", profile.maxTotalWon)
                put("productUrlSnapshot", profile.productUrlSnapshot); put("quantitySnapshot", profile.quantitySnapshot)
                put("enabled", false); put("revisionId", profile.revisionId)
            }) }
        }.toString()

        fun encodeAttempts(attempts: List<AutoBuyAttempt>): String = JSONArray().apply {
            attempts.forEach { attempt -> put(JSONObject().apply {
                put("id", attempt.id); put("itemId", attempt.itemId); put("cycleId", attempt.cycleId)
                put("profileRevisionId", attempt.profileRevisionId); put("dryRun", attempt.dryRun)
                put("status", attempt.status.name); put("message", attempt.message)
                put("orderId", attempt.orderId ?: JSONObject.NULL); put("totalWon", attempt.totalWon ?: JSONObject.NULL)
                put("externalMutationAtEpochMillis", attempt.externalMutationAtEpochMillis ?: JSONObject.NULL)
                put("paymentSubmittedAtEpochMillis", attempt.paymentSubmittedAtEpochMillis ?: JSONObject.NULL)
                put("createdAtEpochMillis", attempt.createdAtEpochMillis); put("updatedAtEpochMillis", attempt.updatedAtEpochMillis)
            }) }
        }.toString()

        fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
        fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
    }
}
