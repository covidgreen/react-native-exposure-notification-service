package ie.gov.tracing.nearby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import ie.gov.tracing.Tracing
import ie.gov.tracing.common.Events
import ie.gov.tracing.common.Events.raiseEvent

class ExposureNotificationRepeaterBroadcastReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Tracing.currentContext = context
        val action = intent!!.action
        Log.d("RN_ENRBroadcastReceiver", "onReceive $action")
        raiseEvent(Events.INFO, "ExposureNotificationRepeaterBroadcastReceiver - received event: $action")
        if (ExposureNotificationRepeater.ACTION_REPEAT_EXPOSURE_NOTIFICATION == action) {
            val builder = StateUpdatedWorker.buildNotification(context)
            val notificationManager = NotificationManagerCompat
                    .from(context)
            notificationManager.notify(RequestCodes.REPEAT_CLOSE_CONTACT_NOTIFICATION, builder.build())
            raiseEvent(Events.INFO, "ExposureNotificationRepeaterBroadcastReceiver - created repeating notification")
        }
    }
}