package dev.quietinbox.platform.capture

import android.content.ActivityNotFoundException
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

    /**
     * The screens that can grant access, most specific first. Some OEM builds ship without one or
     * more of these activities, so callers try them in order (QI-CAPTURE-014).
     */
    fun settingsIntents(): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= 30) {
            add(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString()),
            )
        }
        add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        add(appInfoIntent())
    }.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /** The most specific settings intent; callers that can react to a failure should use [openSettings]. */
    fun settingsIntent(): Intent = settingsIntents().first()

    /**
     * Opens the first settings screen that starts on this device: listener detail → listener list →
     * app info. Each intent is simply tried: a `resolveActivity` pre-check would need `<queries>`
     * entries under Android 11+ package visibility and can report "absent" for screens an OEM
     * moved into another package. Returns false when none could be started, so the caller can
     * show the manual path (QI-CAPTURE-014).
     */
    fun openSettings(from: Context = context): Boolean {
        for (intent in settingsIntents()) {
            try {
                from.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Not on this build: try the next one.
            } catch (_: SecurityException) {
                // Some OEM settings activities are not exported to third parties.
            }
        }
        return false
    }

    /** Android 13+ restricted-settings guidance lives in the app-info screen. */
    fun appInfoIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(android.net.Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun requestRebind() {
        runCatching { android.service.notification.NotificationListenerService.requestRebind(component) }
    }
}
