package dev.quietinbox.platform.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Thin system entry point. Every callback returns immediately after handing the notification to
 * [CaptureCoordinator]; no database, network, bitmap decoding or reflection happens here.
 *
 * This service never calls `cancelNotification`, never fires `contentIntent` / `deleteIntent`,
 * never sends `RemoteInput` — viewing a copy must not change the source (plan section 12).
 */
@AndroidEntryPoint
class QuietInboxListenerService : NotificationListenerService() {

    @Inject
    lateinit var coordinator: CaptureCoordinator

    override fun onListenerConnected() {
        super.onListenerConnected()
        coordinator.onConnected(this)
    }

    override fun onListenerDisconnected() {
        coordinator.onDisconnected()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        coordinator.onPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn ?: return
        coordinator.onRemoved(sbn, reason)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        coordinator.onRemoved(sbn, -1)
    }
}
