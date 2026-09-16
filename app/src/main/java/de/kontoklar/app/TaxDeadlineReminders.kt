package de.kontoklar.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val TAX_REMINDER_CHANNEL_ID = "user_tax_deadlines"
private const val TAX_REMINDER_ACTION = "de.kontoklar.app.USER_TAX_DEADLINE"
private const val TAX_REMINDER_ID = "tax_deadline_id"
private const val TAX_REMINDER_DATE = "tax_deadline_date"

fun taxDeadlineReminderAtMillis(dueDate: String, zone: ZoneId, now: Instant = Instant.now()): Long? {
    val date = runCatching { LocalDate.parse(dueDate) }.getOrNull() ?: return null
    val trigger = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
    return trigger.takeIf { it > now.toEpochMilli() }
}

object TaxDeadlineReminderScheduler {
    private fun preferences(context: Context) = context.getSharedPreferences("tax_deadline_reminders_v1", Context.MODE_PRIVATE)

    fun schedule(context: Context, deadline: TaxDeadline) {
        if (deadline.completed) {
            cancel(context, deadline.id)
            return
        }
        val triggerAt = taxDeadlineReminderAtMillis(deadline.dueDate, ZoneId.systemDefault()) ?: run {
            cancelAlarm(context, deadline.id)
            clearScheduledMarker(context, deadline.id)
            return
        }
        val prefs = preferences(context)
        if (prefs.getString("notified_${deadline.id}", null) == deadline.dueDate) {
            clearScheduledMarker(context, deadline.id)
            return
        }
        if (!InvoiceReminderScheduler.notificationsAllowed(context)) {
            cancelAlarm(context, deadline.id)
            clearScheduledMarker(context, deadline.id)
            return
        }
        val key = "scheduled_${deadline.id}"
        if (prefs.getString(key, null) == deadline.dueDate && pendingIntent(context, deadline.id, deadline.dueDate, PendingIntent.FLAG_NO_CREATE) != null) return
        cancelAlarm(context, deadline.id)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = pendingIntent(context, deadline.id, deadline.dueDate, PendingIntent.FLAG_UPDATE_CURRENT)
            ?: error("Die Steuertermin-Erinnerung konnte nicht eingerichtet werden.")
        alarm.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            operation
        )
        prefs.edit().putString(key, deadline.dueDate).apply()
        updateScheduledIds(context, deadline.id, include = true)
    }

    fun cancel(context: Context, deadlineId: String) {
        cancelAlarm(context, deadlineId)
        NotificationManagerCompat.from(context).cancel(notificationId(deadlineId))
        preferences(context).edit().remove("notified_$deadlineId").remove("scheduled_$deadlineId").apply()
        updateScheduledIds(context, deadlineId, include = false)
    }

    fun reconcile(context: Context, deadlines: List<TaxDeadline>, forceSchedule: Boolean = false) {
        val activeIds = deadlines.filterNot(TaxDeadline::completed).mapTo(mutableSetOf(), TaxDeadline::id)
        val scheduledIds = preferences(context).getStringSet("scheduled_deadline_ids", emptySet()).orEmpty().toSet()
        (scheduledIds - activeIds).forEach { cancel(context, it) }
        deadlines.forEach { deadline ->
            if (deadline.completed) cancel(context, deadline.id)
            else if (forceSchedule) {
                cancelAlarm(context, deadline.id)
                clearScheduledMarker(context, deadline.id)
                schedule(context, deadline)
            } else schedule(context, deadline)
        }
    }

    internal fun expectedDate(intent: Intent): String? = intent.getStringExtra(TAX_REMINDER_DATE)
    internal fun deadlineId(intent: Intent): String? = intent.getStringExtra(TAX_REMINDER_ID)
    internal fun notificationId(deadlineId: String): Int = deadlineId.hashCode() xor 0x544158

    internal fun alreadyNotified(context: Context, deadlineId: String, date: String): Boolean =
        preferences(context).getString("notified_$deadlineId", null) == date

    internal fun markNotified(context: Context, deadlineId: String, date: String) {
        preferences(context).edit().putString("notified_$deadlineId", date).remove("scheduled_$deadlineId").apply()
        updateScheduledIds(context, deadlineId, include = false)
    }

    internal fun alarmUnavailable(context: Context, deadlineId: String) {
        preferences(context).edit().remove("scheduled_$deadlineId").apply()
        updateScheduledIds(context, deadlineId, include = false)
    }

    private fun pendingIntent(context: Context, id: String, date: String, flags: Int): PendingIntent? {
        val intent = Intent(context, TaxDeadlineReminderReceiver::class.java)
            .setAction(TAX_REMINDER_ACTION)
            .setData(android.net.Uri.parse("kontoklar-tax://deadline/$id"))
            .putExtra(TAX_REMINDER_ID, id)
            .putExtra(TAX_REMINDER_DATE, date)
        return PendingIntent.getBroadcast(context, notificationId(id), intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancelAlarm(context: Context, id: String) {
        val intent = Intent(context, TaxDeadlineReminderReceiver::class.java)
            .setAction(TAX_REMINDER_ACTION)
            .setData(android.net.Uri.parse("kontoklar-tax://deadline/$id"))
        PendingIntent.getBroadcast(context, notificationId(id), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            ?.let { (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(it) }
    }

    private fun clearScheduledMarker(context: Context, id: String) {
        preferences(context).edit().remove("scheduled_$id").apply()
        updateScheduledIds(context, id, include = false)
    }

    private fun updateScheduledIds(context: Context, id: String, include: Boolean) {
        val prefs = preferences(context)
        val ids = prefs.getStringSet("scheduled_deadline_ids", emptySet()).orEmpty().toMutableSet()
        if (include) ids.add(id) else ids.remove(id)
        prefs.edit().putStringSet("scheduled_deadline_ids", ids).apply()
    }
}

class TaxDeadlineReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = TaxDeadlineReminderScheduler.deadlineId(intent) ?: return
        val expectedDate = TaxDeadlineReminderScheduler.expectedDate(intent) ?: return
        val deadline = LocalData(context).taxDeadlines().firstOrNull { it.id == id }
        if (deadline == null || deadline.completed || deadline.dueDate != expectedDate) {
            TaxDeadlineReminderScheduler.cancel(context, id)
            return
        }
        if (TaxDeadlineReminderScheduler.alreadyNotified(context, id, expectedDate)) return
        if (!InvoiceReminderScheduler.notificationsAllowed(context)) {
            TaxDeadlineReminderScheduler.alarmUnavailable(context, id)
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(TAX_REMINDER_CHANNEL_ID, "Eigene Steuertermine", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Erinnerungen für selbst eingetragene Steuertermine"
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            TaxDeadlineReminderScheduler.notificationId(id),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, TAX_REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Eigener Steuertermin heute")
            .setContentText("Prüfe den von dir eingetragenen Termin in KontoKlar.")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TaxDeadlineReminderScheduler.notificationId(id), notification)
            TaxDeadlineReminderScheduler.markNotified(context, id, expectedDate)
        } catch (_: SecurityException) {
            TaxDeadlineReminderScheduler.alarmUnavailable(context, id)
        }
    }
}
