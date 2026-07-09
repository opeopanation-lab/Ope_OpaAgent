package ai.affiora.ope_opaAgent.tools

import ai.affiora.ope_opaAgent.data.model.StructuredToolError
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.util.Calendar

class AlarmTimerTool(
    private val context: Context,
    /**
     * Injectable for testing. Production passes the real system AlarmManager.
     */
    private val alarmManagerProvider: () -> AlarmManager? = {
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    },
) : AndroidTool {

    override val name: String = "alarm"

    override val description: String =
        "Set alarms, timers, or show existing alarms. Actions: 'set_alarm' (hour 0-23, minute 0-59, label), 'set_timer' (seconds, label), 'show_alarms'. Delegates to the system clock app."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("set_alarm"))
                    add(JsonPrimitive("set_timer"))
                    add(JsonPrimitive("show_alarms"))
                })
                put("description", JsonPrimitive("The action to perform."))
            })
            put("hour", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Hour for alarm (0-23). Required for 'set_alarm'."))
            })
            put("minute", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Minute for alarm (0-59). Required for 'set_alarm'."))
            })
            put("seconds", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Timer duration in seconds. Required for 'set_timer'."))
            })
            put("label", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Optional label for the alarm or timer."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return withContext(Dispatchers.IO) {
            when (action) {
                "set_alarm" -> executeSetAlarm(params)
                "set_timer" -> executeSetTimer(params)
                "show_alarms" -> executeShowAlarms()
                else -> ToolResult.Error("Unknown action: $action. Must be 'set_alarm', 'set_timer', or 'show_alarms'.")
            }
        }
    }

    private fun executeSetAlarm(params: Map<String, JsonElement>): ToolResult {
        val hour = params["hour"]?.jsonPrimitive?.int
            ?: return ToolResult.Error("Missing required parameter: hour")
        val minute = params["minute"]?.jsonPrimitive?.int
            ?: return ToolResult.Error("Missing required parameter: minute")
        val label = (params["label"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }) ?: "Alarm"

        if (hour !in 0..23 || minute !in 0..59) {
            return ToolResult.Error("Hour must be 0-23 and minute must be 0-59.")
        }

        // Item A (v1.2.12): the previous startActivity(ACTION_SET_ALARM) path was silently
        // dropped by Android 10+ Background Activity Start restrictions when invoked from
        // a channel handler. We now register the alarm with AlarmManager.setAlarmClock,
        // which works from background context and the system surfaces it on lockscreen +
        // status bar (verifiable via AlarmManager.getNextAlarmClock).
        val alarmManager = alarmManagerProvider()
            ?: return ToolResult.Error("AlarmManager unavailable on this device.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "alarm_blocked_no_exact_alarm_permission: api=${Build.VERSION.SDK_INT}")
            val err = StructuredToolError(
                errorType = StructuredToolError.ERROR_TYPE_PERMISSION_DENIED,
                permission = "android.permission.SCHEDULE_EXACT_ALARM",
                actionHint = "Exact alarm permission is required to set alarms from chat. " +
                    "Open Settings → Apps → ope_opaAgent → Alarms & reminders to grant it, " +
                    "then ask me again.",
                deepLinkIntent = "android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
            )
            return ToolResult.Error(err.toJsonString())
        }

        val triggerTime = nextOccurrenceMillis(hour, minute)
        val notificationId = ((hour * 100) + minute) // stable id per HH:MM

        val fireIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(AlarmReceiver.EXTRA_LABEL, label)
            putExtra(AlarmReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val firePending = PendingIntent.getBroadcast(
            context,
            notificationId,
            fireIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Show-intent — what tapping the lockscreen alarm chip opens (the app)
        val showIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val showPending = PendingIntent.getActivity(
            context,
            notificationId,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return try {
            val info = AlarmManager.AlarmClockInfo(triggerTime, showPending)
            alarmManager.setAlarmClock(info, firePending)
            Log.i(TAG, "alarm_scheduled: trigger=$triggerTime label=$label hour=$hour min=$minute")
            ToolResult.Success(
                "Alarm scheduled for %02d:%02d ($label). It will trigger a high-priority alarm ".format(hour, minute) +
                    "notification at that time. Tip: ensure ope_opaAgent notifications are enabled and " +
                    "Do-Not-Disturb is configured to allow alarms, otherwise the sound may be muted. " +
                    "View your next alarm in the lockscreen or status-bar alarm chip."
            )
        } catch (e: SecurityException) {
            // Some OEMs throw SecurityException despite canScheduleExactAlarms returning true.
            Log.w(TAG, "alarm_security_exception: ${e.message}")
            val err = StructuredToolError(
                errorType = StructuredToolError.ERROR_TYPE_PERMISSION_DENIED,
                permission = "android.permission.SCHEDULE_EXACT_ALARM",
                actionHint = "Cannot set exact alarm: ${e.message ?: "device denied"}. " +
                    "Open Settings → Apps → ope_opaAgent → Alarms & reminders to verify access.",
                deepLinkIntent = "android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
            )
            ToolResult.Error(err.toJsonString())
        } catch (e: Exception) {
            ToolResult.Error("Failed to schedule alarm: ${e.message}")
        }
    }

    /**
     * Compute the next wall-clock occurrence of HH:MM as Unix millis.
     * If the requested time is earlier than now, roll forward 24 hours.
     */
    internal fun nextOccurrenceMillis(hour: Int, minute: Int, now: Calendar = Calendar.getInstance()): Long {
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    private fun executeSetTimer(params: Map<String, JsonElement>): ToolResult {
        val seconds = params["seconds"]?.jsonPrimitive?.int
            ?: return ToolResult.Error("Missing required parameter: seconds")
        val label = params["label"]?.jsonPrimitive?.content ?: ""

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Timer set for ${seconds}s${if (label.isNotEmpty()) " ($label)" else ""}")
        } catch (e: Exception) {
            ToolResult.Error("Failed to set timer: ${e.message}")
        }
    }

    private fun executeShowAlarms(): ToolResult {
        // show_alarms requires opening a system Activity. From a background channel
        // handler this is silently denied by BAS. We surface that explicitly instead
        // of returning fake Success.
        return try {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Opened alarms view.")
        } catch (e: Exception) {
            ToolResult.Error(
                "Cannot open the alarms view from chat. Tell the user: " +
                    "open the Clock app on the device to see existing alarms."
            )
        }
    }

    companion object {
        private const val TAG = "AlarmTimerTool"
    }
}
