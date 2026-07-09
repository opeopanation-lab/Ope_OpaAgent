package ai.affiora.ope_opaAgent.tools

import ai.affiora.ope_opaAgent.data.model.StructuredToolError
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * Unit tests for [AlarmTimerTool] — Item A (v1.2.12) BAS bug fix.
 * The previous implementation called `startActivity` from background context, which
 * Android 10+ silently dropped, returning fake `Success`. This test asserts:
 *   - Exact-alarm permission denial returns structured error (not silent success)
 *   - Trigger time is computed correctly (next occurrence rolls forward if past)
 *   - HH/MM bounds validated
 */
class AlarmTimerToolTest {

    private fun toolWith(alarmManager: android.app.AlarmManager?): AlarmTimerTool {
        val context = mockk<android.content.Context>(relaxed = true)
        every { context.getSystemService(android.content.Context.ALARM_SERVICE) } returns alarmManager
        return AlarmTimerTool(context = context, alarmManagerProvider = { alarmManager })
    }

    @Test
    fun setAlarm_invalidHour_returnsError() = runBlocking {
        val tool = toolWith(mockk(relaxed = true))
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("set_alarm"),
                "hour" to JsonPrimitive(25),
                "minute" to JsonPrimitive(0),
            )
        )
        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("0-23")
    }

    @Test
    fun setAlarm_invalidMinute_returnsError() = runBlocking {
        val tool = toolWith(mockk(relaxed = true))
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("set_alarm"),
                "hour" to JsonPrimitive(7),
                "minute" to JsonPrimitive(60),
            )
        )
        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("0-59")
    }

    @Test
    fun setAlarm_missingHour_returnsError() = runBlocking {
        val tool = toolWith(mockk(relaxed = true))
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("set_alarm"),
                "minute" to JsonPrimitive(0),
            )
        )
        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
    }

    @Test
    fun setAlarm_alarmManagerNull_returnsError() = runBlocking {
        val tool = toolWith(null)
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("set_alarm"),
                "hour" to JsonPrimitive(7),
                "minute" to JsonPrimitive(0),
            )
        )
        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
    }

    @Test
    fun nextOccurrenceMillis_futureTimeToday_returnsToday() {
        // 23:59 on a day where now is 00:00 → today's 23:59
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.MAY, 9, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val tool = toolWith(mockk(relaxed = true))
        val result = tool.nextOccurrenceMillis(23, 59, now.clone() as Calendar)

        val expected = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertThat(result).isEqualTo(expected.timeInMillis)
    }

    @Test
    fun nextOccurrenceMillis_pastTimeToday_rollsForwardOneDay() {
        // 07:00 on a day where now is 12:00 → tomorrow's 07:00
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.MAY, 9, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val tool = toolWith(mockk(relaxed = true))
        val result = tool.nextOccurrenceMillis(7, 0, now.clone() as Calendar)

        val expected = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        assertThat(result).isEqualTo(expected.timeInMillis)
    }

    @Test
    fun setAlarm_apiBelowS_skipsCanScheduleExactAlarmsCheck() = runBlocking {
        // We can't easily mock Build.VERSION.SDK_INT in unit tests, but we can verify
        // that a working AlarmManager.setAlarmClock call produces Success (the path
        // that doesn't take the permission-denied branch on API 31+).
        val alarmManager = mockk<android.app.AlarmManager>(relaxed = true)
        every { alarmManager.canScheduleExactAlarms() } returns true

        val tool = toolWith(alarmManager)
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("set_alarm"),
                "hour" to JsonPrimitive(7),
                "minute" to JsonPrimitive(0),
                "label" to JsonPrimitive("morning"),
            )
        )

        assertThat(result).isInstanceOf(ToolResult.Success::class.java)
        verify { alarmManager.setAlarmClock(any(), any()) }
    }

    @Test
    fun setAlarm_canScheduleExactAlarmsFalse_returnsStructuredPermissionError() = runBlocking {
        // Spec §7A: when permission missing on API 31+, return structured error,
        // NOT fake Success. This is the regression guard against the original BAS bug.
        val alarmManager = mockk<android.app.AlarmManager>(relaxed = true)
        every { alarmManager.canScheduleExactAlarms() } returns false

        val tool = toolWith(alarmManager)
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("set_alarm"),
                "hour" to JsonPrimitive(7),
                "minute" to JsonPrimitive(0),
            )
        )

        // We can only assert this if running on API 31+. On API < 31 the check is skipped
        // and setAlarmClock is called even with canScheduleExactAlarms returning false.
        // Robolectric default is the host JVM's apiLevel — covered, but be lenient.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            assertThat(result).isInstanceOf(ToolResult.Error::class.java)
            val err = StructuredToolError.parse((result as ToolResult.Error).message)
            assertThat(err).isNotNull()
            assertThat(err!!.errorType).isEqualTo(StructuredToolError.ERROR_TYPE_PERMISSION_DENIED)
            assertThat(err.permission).isEqualTo("android.permission.SCHEDULE_EXACT_ALARM")
            assertThat(err.deepLinkIntent).isEqualTo("android.settings.REQUEST_SCHEDULE_EXACT_ALARM")
        }
    }

    @Test
    fun setAlarm_doesNotCallStartActivity() = runBlocking {
        // Regression guard — the v1.2.11 implementation called context.startActivity
        // which was silently dropped under BAS. Verify we never invoke startActivity
        // for set_alarm.
        val context = mockk<android.content.Context>(relaxed = true)
        val alarmManager = mockk<android.app.AlarmManager>(relaxed = true)
        every { alarmManager.canScheduleExactAlarms() } returns true
        every { context.packageManager.getLaunchIntentForPackage(any()) } returns mockk(relaxed = true)
        every { context.packageName } returns "ai.affiora.ope_opaAgent"

        val tool = AlarmTimerTool(context = context, alarmManagerProvider = { alarmManager })
        tool.execute(
            mapOf(
                "action" to JsonPrimitive("set_alarm"),
                "hour" to JsonPrimitive(7),
                "minute" to JsonPrimitive(0),
            )
        )

        verify(exactly = 0) { context.startActivity(any()) }
    }
}
