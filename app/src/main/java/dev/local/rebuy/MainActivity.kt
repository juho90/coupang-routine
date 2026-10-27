package dev.local.rebuy

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.widget.Toast
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
import dev.local.rebuy.importproduct.IncomingProductImage
import dev.local.rebuy.pcimport.PcImageInbox
import dev.local.rebuy.ui.RebuyApp
import dev.local.rebuy.ui.RoutineViewModel

data class NotificationCompletionRequest(val itemId: String, val cycleId: String)

class MainActivity : ComponentActivity() {
    private var completionRequest by mutableStateOf<NotificationCompletionRequest?>(null)
    private var incomingImage by mutableStateOf<IncomingProductImage?>(null)
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
        if (savedInstanceState?.getBoolean(IMAGE_INTENT_HANDLED) == true) {
            val id = savedInstanceState.getString(PENDING_IMAGE_ID)
            val uri = savedInstanceState.getString(PENDING_IMAGE_URI)
            incomingImage = if (id != null && uri != null) runCatching {
                IncomingProductImage(id, IncomingProductImage.requireContentUri(Uri.parse(uri)))
            }.getOrNull() else null
        } else receiveImage(intent)
        setContent {
            val displayedImage = incomingImage
            RebuyApp(
                viewModel = viewModel,
                completionRequest = completionRequest,
                onCompletionRequestConsumed = {
                    completionRequest = null
                    intent.removeExtra(ReminderIntents.EXTRA_ITEM_ID)
                    intent.removeExtra(ReminderIntents.EXTRA_CYCLE_ID)
                },
                openProduct = ::openProduct,
                incomingImage = displayedImage,
                onIncomingImageConsumed = {
                    // An older OCR callback must not consume a newly delivered image intent.
                    if (incomingImage?.id == displayedImage?.id) {
                        incomingImage = null
                        clearImageExtras(intent)
                    }
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        completionRequest = intent.completionRequest()
        receiveImage(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(IMAGE_INTENT_HANDLED, true)
        incomingImage?.let {
            outState.putString(PENDING_IMAGE_ID, it.id)
            outState.putString(PENDING_IMAGE_URI, it.uri.toString())
        }
        super.onSaveInstanceState(outState)
    }

    private fun receiveImage(intent: Intent) {
        try {
            val next = IncomingProductImage.fromIntent(intent) ?: return
            incomingImage?.takeIf { it.uri != next.uri }?.let { PcImageInbox.release(this, it.uri) }
            incomingImage = next
        } catch (_: Exception) {
            clearImageExtras(intent)
            Toast.makeText(this, "상품 이미지 한 장을 다시 공유해 주세요. 주소나 텍스트는 이미지로 가져오지 않습니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun clearImageExtras(intent: Intent) {
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.clipData = null
    }

    private fun Intent.completionRequest(): NotificationCompletionRequest? {
        val itemId = getStringExtra(ReminderIntents.EXTRA_ITEM_ID)?.takeIf(String::isNotBlank)
        val cycleId = getStringExtra(ReminderIntents.EXTRA_CYCLE_ID)?.takeIf(String::isNotBlank)
        return if (itemId != null && cycleId != null) NotificationCompletionRequest(itemId, cycleId) else null
    }

    private fun openProduct(rawUrl: String): Boolean = ProductLauncher.open(this, rawUrl)

    private companion object {
        const val IMAGE_INTENT_HANDLED = "incoming_image_intent_handled"
        const val PENDING_IMAGE_ID = "incoming_image_id"
        const val PENDING_IMAGE_URI = "incoming_image_uri"
    }
}
