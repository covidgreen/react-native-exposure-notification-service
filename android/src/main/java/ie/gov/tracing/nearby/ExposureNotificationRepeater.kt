package ie.gov.tracing.nearby

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import ie.gov.tracing.common.Events
import ie.gov.tracing.storage.SharedPrefs

object ExposureNotificationRepeater {
    const val ACTION_REPEAT_EXPOSURE_NOTIFICATION = "com.google.android.apps.exposurenotification.ACTION_REPEAT_EXPOSURE_NOTIFICATION"

    var pendingIntent: PendingIntent? = null

    private fun notificationRepeatAsMilliseconds(context: Context): Long {
        val notificationRepeat = SharedPrefs.getLong("notificationRepeat", context)
        return notificationRepeat * 60 * 1000
    }

    private fun buildIntent(context: Context): Intent {
        return Intent(context.applicationContext, ExposureNotificationRepeaterBroadcastReceiver::class.java).apply {
            action = ACTION_REPEAT_EXPOSURE_NOTIFICATION
        }
    }

    @JvmStatic
    fun setup(context: Context) {
        Events.raiseEvent(Events.INFO, "ExposureNotificationRepeater - setup")
        val repeatDurationInMs = notificationRepeatAsMilliseconds(context)

        if (repeatDurationInMs == 0L) {
            Events.raiseEvent(Events.INFO, "ExposureNotificationRepeater - will not repeat the notification ")
            return;
        }

        Events.raiseEvent(Events.INFO, "ExposureNotificationRepeater - will repeat Exposure Notification every $repeatDurationInMs ms")
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Events.raiseEvent(Events.ERROR, "ExposureNotificationRepeater - No alarmManager available")
            return
        }

        val intent = buildIntent(context)

        val pendingIntent = PendingIntent.getBroadcast(context, RequestCodes.REPEAT_CLOSE_CONTACT_NOTIFICATION, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        alarmManager.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + repeatDurationInMs, repeatDurationInMs, pendingIntent)
        Events.raiseEvent(Events.INFO, "ExposureNotificationRepeater - Exposure Notification repeat alarm set")
    }

    @JvmStatic
    fun cancel(context: Context) {
        Events.raiseEvent(Events.INFO, "ExposureNotificationRepeater - cancel")
        val intent: Intent = buildIntent(context)
        val alarmManager =
                context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Events.raiseEvent(Events.ERROR, "ExposureNotificationRepeater - Could not cancel any pending intent: No alarmManager!")
            return
        }

        val pendingIntent = PendingIntent.getBroadcast(context, RequestCodes.REPEAT_CLOSE_CONTACT_NOTIFICATION, intent, PendingIntent.FLAG_NO_CREATE)
        if(pendingIntent == null) {
            Events.raiseEvent(Events.INFO, "ExposureNotificationRepeater - No PendingIntent found")
            return
        }

        Events.raiseEvent(Events.INFO, "ExposureNotificationRepeater - Removing pending intent")
        alarmManager.cancel(pendingIntent)
        Events.raiseEvent(Events.INFO, "ExposureNotificationRepeater - Removed pending intent")

    }

}