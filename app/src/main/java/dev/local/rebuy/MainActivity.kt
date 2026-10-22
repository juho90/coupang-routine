package dev.local.rebuy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.local.rebuy.reminders.ReminderIntents
import dev.local.rebuy.ui.RebuyApp
import dev.local.rebuy.ui.RoutineViewModel

data class NotificationCompletionRequest(val itemId: String, val cycleId: String)

class MainActivity : ComponentActivity() {
    private var completionRequest by mutableStateOf<NotificationCompletionRequest?>(null)
    private val viewModel: RoutineViewModel by viewModels {
        val container = (application as RebuyApplication).container
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(RoutineViewModel::class.java))
                return RoutineViewModel(container.repository, container.scheduler, container.settings) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        completionRequest = intent.completionRequest()
        setContent {
            RebuyApp(
                viewModel = viewModel,
                completionRequest = completionRequest,
                onCompletionRequestConsumed = {
                    completionRequest = null
                    intent.removeExtra(ReminderIntents.EXTRA_ITEM_ID)
                    intent.removeExtra(ReminderIntents.EXTRA_CYCLE_ID)
                },
                openProduct = ::openProduct,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        completionRequest = intent.completionRequest()
    }

    private fun Intent.completionRequest(): NotificationCompletionRequest? {
        val itemId = getStringExtra(ReminderIntents.EXTRA_ITEM_ID)?.takeIf(String::isNotBlank)
        val cycleId = getStringExtra(ReminderIntents.EXTRA_CYCLE_ID)?.takeIf(String::isNotBlank)
        return if (itemId != null && cycleId != null) NotificationCompletionRequest(itemId, cycleId) else null
    }

    /** Opening Coupang is always a user action. No credentials or payment flows enter this app. */
    private fun openProduct(rawUrl: String): Boolean = ProductLauncher.open(this, rawUrl)
}
