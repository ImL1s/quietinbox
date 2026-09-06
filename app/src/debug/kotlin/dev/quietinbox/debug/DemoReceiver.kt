package dev.quietinbox.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.quietinbox.platform.storage.repo.DemoData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only entry point for the synthetic demo vault, so a screenshot run can fill and empty the
 * app without touching a real messaging app:
 *
 * ```
 * adb shell am broadcast -a dev.quietinbox.debug.DEMO --es op seed \
 *     -n dev.quietinbox.app.debug/dev.quietinbox.debug.DemoReceiver
 * adb shell am broadcast -a dev.quietinbox.debug.DEMO --es op clear \
 *     -n dev.quietinbox.app.debug/dev.quietinbox.debug.DemoReceiver
 * ```
 *
 * The class lives in the debug source set only, so it does not exist in a release build. A receiver
 * cannot be constructor-injected, hence the Hilt entry point; the vault has to be opened off the
 * main thread, hence `goAsync` plus a coroutine.
 */
class DemoReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DemoEntryPoint {
        fun demoDataRepository(): DemoData
    }

    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.getStringExtra(EXTRA_OP)
        if (op != OP_SEED && op != OP_CLEAR) {
            Log.w(TAG, "ignoring broadcast with op=$op; expected \"$OP_SEED\" or \"$OP_CLEAR\"")
            return
        }
        val application = context.applicationContext
        val pending = goAsync()
        // The scope is owned here and nothing cancels it, so the receiver always finishes.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = EntryPointAccessors
                    .fromApplication(application, DemoEntryPoint::class.java)
                    .demoDataRepository()
                when (op) {
                    OP_SEED -> {
                        // `--es lang ja-JP` names the demo's language; without it the app's own configuration decides.
                        val locale = intent.getStringExtra(EXTRA_LANG)?.takeIf { it.isNotBlank() }?.let { java.util.Locale.forLanguageTag(it) }
                        Log.i(TAG, "demo seed done: ${repository.seed(locale = locale)}")
                    }
                    OP_CLEAR -> {
                        repository.clear()
                        Log.i(TAG, "demo clear done")
                    }
                }
            } catch (t: Throwable) {
                // A locked vault is the expected failure here; report it instead of crashing adb.
                Log.e(TAG, "demo $op failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "QuietInboxDemo"
        const val EXTRA_OP = "op"
        const val OP_SEED = "seed"
        const val EXTRA_LANG = "lang"
        const val OP_CLEAR = "clear"
    }
}
