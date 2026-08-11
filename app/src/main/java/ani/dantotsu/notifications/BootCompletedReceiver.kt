package ani.dantotsu.notifications

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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

class AlarmPermissionStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            PrefManager.init(context)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val canScheduleExactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }
            if (canScheduleExactAlarms) {
                TaskScheduler.create(context, false).cancelAllTasks()
                TaskScheduler.create(context, true).scheduleAllTasks(context)
            } else {
                TaskScheduler.create(context, true).cancelAllTasks()
                TaskScheduler.create(context, false).scheduleAllTasks(context)
            }
            PrefManager.setVal(PrefName.UseAlarmManager, canScheduleExactAlarms)
        }
    }
}
