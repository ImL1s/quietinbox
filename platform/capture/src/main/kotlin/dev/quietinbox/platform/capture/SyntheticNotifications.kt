package dev.quietinbox.platform.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts clearly marked synthetic notifications from QuietInbox's own package so users can see the
 * pipeline work before granting access to a real source (plan section 12, L2 in section 15).
 * Only notifications carrying [EXTRA_SYNTHETIC] are captured from our own package; reminders are
 * never captured, which prevents feedback loops.
 */
@Singleton
class SyntheticNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun canPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Posts a MessagingStyle conversation with [count] messages and returns the notification id. */
    fun postConversation(count: Int = 3, iconRes: Int): Int {
        ensureChannel()
        val now = System.currentTimeMillis()
        val alice = Person.Builder().setName("測試聯絡人 Alice").setKey("synthetic:alice").build()
        val bob = Person.Builder().setName("測試聯絡人 Bob").setKey("synthetic:bob").build()
        val me = Person.Builder().setName("我").setKey("synthetic:me").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("QuietInbox 測試群組")
            .setGroupConversation(true)
        val samples = listOf(
            "這是一則合成測試訊息 👋",
            "副本會保存在你的加密收件匣裡",
            "看副本不會讓來源 App 標記已讀 ✅",
            "同一段文字出現兩次也會被分開保留",
            "同一段文字出現兩次也會被分開保留",
            "支援 Emoji 統計 😀🎉🇹🇼",
        )
        for (i in 0 until count.coerceIn(1, samples.size)) {
            style.addMessage(samples[i], now - (count - i) * 60_000L, if (i % 2 == 0) alice else bob)
        }
        val extras = Bundle().apply { putBoolean(EXTRA_SYNTHETIC, true) }
        val id = SYNTHETIC_ID
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setStyle(style)
            .setShortcutId("synthetic-conversation")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addExtras(extras)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(SYNTHETIC_TAG, id, notification) }
        return id
    }

    /** Posts a single big-text message (simulates a plain source without MessagingStyle). */
    fun postPlain(title: String, body: String, iconRes: Int, tag: String = "synthetic-plain"): Int {
        ensureChannel()
        val extras = Bundle().apply { putBoolean(EXTRA_SYNTHETIC, true) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addExtras(extras)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(tag, SYNTHETIC_ID + 1, notification) }
        return SYNTHETIC_ID + 1
    }

    fun cancelAll() {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(SYNTHETIC_TAG, SYNTHETIC_ID)
        nm.cancel("synthetic-plain", SYNTHETIC_ID + 1)
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Synthetic test notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Only used to demonstrate capture; never contains real messages."
                },
            )
        }
    }

    companion object {
        const val EXTRA_SYNTHETIC = "dev.quietinbox.synthetic"
        const val CHANNEL_ID = "quietinbox.synthetic"
        const val SYNTHETIC_TAG = "synthetic-conversation"
        const val SYNTHETIC_ID = 4_242
    }
}
