package dev.local.rebuy.reminders

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import dev.local.rebuy.RebuyApplication
import dev.local.rebuy.ProductLauncher
import dev.local.rebuy.domain.CoupangUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Private Activity PendingIntent target; never completes a purchase or accepts an arbitrary URL. */
class OpenProductActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val itemId = intent.getStringExtra(ReminderIntents.EXTRA_ITEM_ID)
        val expectedCycleId = intent.getStringExtra(ReminderIntents.EXTRA_CYCLE_ID)
        if (itemId == null || expectedCycleId == null) {
            finish()
            return
        }
        activityScope.launch {
            try {
                val item = (applicationContext as RebuyApplication).container.repository.getItem(itemId)
                if (item == null || item.archived || !item.enabled || item.cycleId != expectedCycleId) {
                    Toast.makeText(this@OpenProductActivity, "변경되거나 완료된 구매 알림입니다.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val url = runCatching { CoupangUrl.normalize(item.productUrl) }.getOrNull()
                if (url != null) {
                    if (!ProductLauncher.open(this@OpenProductActivity, url)) {
                        Toast.makeText(this@OpenProductActivity, "상품을 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@OpenProductActivity, "상품 주소를 다시 확인해 주세요.", Toast.LENGTH_SHORT).show()
                }
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this@OpenProductActivity, "상품을 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(this@OpenProductActivity, "상품을 열지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}
