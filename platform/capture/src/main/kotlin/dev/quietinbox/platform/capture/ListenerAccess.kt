package dev.quietinbox.platform.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Notification-listener permission helpers. Never bypasses the system flow (plan section 12). */
@Singleton
class ListenerAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val component: ComponentName = ComponentName(context, QuietInboxListenerService::class.java)

    fun isGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Opens the system screen for this listener when supported, otherwise the generic list. */
    fun settingsIntent(): Intent {
        val intent = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Android 13+ restricted-settings guidance lives in the app-info screen. */
    fun appInfoIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(android.net.Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun requestRebind() {
        runCatching { android.service.notification.NotificationListenerService.requestRebind(component) }
    }
}
