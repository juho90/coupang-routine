package dev.local.rebuy.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.local.rebuy.MainActivity
import dev.local.rebuy.R
import dev.local.rebuy.data.RoutineItem

internal class ReminderNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "구매 예정일 알림",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "등록한 상품의 다음 구매일을 알려 드립니다." }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun show(item: RoutineItem): Boolean {
        createChannel()
        if (!canNotify()) return false

        // The private Activity checks the current cycle before starting the HTTPS ACTION_VIEW.
        val openIntent = Intent(context, OpenProductActivity::class.java).apply {
            data = ReminderIntents.identity("open", item.id, item.cycleId)
            putExtra(ReminderIntents.EXTRA_ITEM_ID, item.id)
            putExtra(ReminderIntents.EXTRA_CYCLE_ID, item.cycleId)
        }
        val confirmIntent = Intent(context, MainActivity::class.java).apply {
            data = ReminderIntents.identity("confirm", item.id, item.cycleId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ReminderIntents.EXTRA_ITEM_ID, item.id)
            putExtra(ReminderIntents.EXTRA_CYCLE_ID, item.cycleId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(context, 0, openIntent, flags)
        val confirm = PendingIntent.getActivity(context, 0, confirmIntent, flags)
        val message = "${item.title} ${item.quantity}개 구매를 확인해 주세요."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("다시 살 때가 되었어요")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "쿠팡 열기", open)
            .addAction(0, "구매 완료 확인", confirm)
            .build()
        return try {
            manager.notify(item.id, NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // Permission may be revoked between checking it and posting the notification.
            false
        }
    }

    fun cancel(itemId: String) = manager.cancel(itemId, NOTIFICATION_ID)

    private companion object {
        const val CHANNEL_ID = "purchase_reminders"
        const val NOTIFICATION_ID = 1
    }
}
