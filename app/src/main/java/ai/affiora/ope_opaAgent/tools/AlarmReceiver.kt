package ai.affiora.ope_opaAgent.tools

import ai.affiora.ope_opaAgent.OpeOpaAgentApplication
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Fires when an alarm scheduled by [AlarmTimerTool] reaches its trigger time.
 * Posts a high-priority notification with the user's label so the alarm is visible
 * even if ope_opaAgent is not in the foreground.
 *
 * Item A (v1.2.12): replaces the previous `startActivity(ACTION_SET_ALARM)` flow that
 * was silently dropped by Android 10+ Background Activity Start restrictions when
 * called from a channel handler context.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Alarm"
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, label.hashCode())

        Log.i(TAG, "alarm_fired: label=$label id=$notificationId")

        val tapIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val tapPending = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, OpeOpaAgentApplication.CHANNEL_AGENT_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label)
            .setContentText("Alarm")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setContentIntent(tapPending)
            .setAutoCancel(true)
            .setFullScreenIntent(tapPending, true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId, notification)
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val EXTRA_LABEL = "label"
        const val EXTRA_NOTIFICATION_ID = "notif_id"
        const val ACTION_FIRE = "ai.affiora.ope_opaAgent.ACTION_ALARM_FIRE"
    }
}
