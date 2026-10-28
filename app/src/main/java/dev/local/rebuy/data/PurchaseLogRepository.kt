package dev.local.rebuy.data

import kotlinx.coroutines.flow.Flow

class PurchaseLogRepository(database: AppDatabase) {
    val logs: Flow<List<PurchaseRecord>> = database.purchaseDao().observeLogs()
}
