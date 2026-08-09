package ani.dantotsu.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            PrefManager.init(context)
            Logger.init(context)
            Logger.log("Starting Dantotsu Notification Service on Boot")
            // FIX: Reprogramar TODAS las tareas con el scheduler activo
            // (antes faltaba SUBSCRIPTION y no se restauraba el modo WorkManager)
            val scheduler =
                TaskScheduler.create(context, PrefManager.getVal(PrefName.UseAlarmManager))
            scheduler.scheduleAllTasks(context)
        }
    }
}
