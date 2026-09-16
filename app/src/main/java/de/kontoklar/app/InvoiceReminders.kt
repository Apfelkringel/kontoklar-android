package de.kontoklar.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.ZoneId

private const val REMINDER_CHANNEL_ID = "invoice_payment_reminders"
private const val EXTRA_INVOICE_ID = "invoice_id"
private const val EXTRA_DUE_DATE = "invoice_due_date"

fun invoiceReminderAtMillis(dueDate: String, zone: ZoneId): Long? {
    val parsedDueDate = runCatching { LocalDate.parse(dueDate) }.getOrNull() ?: return null
    return parsedDueDate.plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
}

object InvoiceReminderScheduler {
    private fun preferences(context: Context) = context.getSharedPreferences("invoice_reminders_v1", Context.MODE_PRIVATE)

    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun schedule(context: Context, invoice: Invoice, force: Boolean = false) {
        if (invoice.status == "Entwurf" || invoiceOutstandingCents(invoice) == 0L) {
            cancel(context, invoice.id)
            return
        }
        val dueDate = runCatching { LocalDate.parse(invoice.dueDate) }.getOrNull() ?: run {
            cancel(context, invoice.id)
            return
        }
        val dueDateText = dueDate.toString()
        val prefs = preferences(context)
        if (prefs.getString("notified_${invoice.id}", null) == dueDateText) {
            clearScheduledMarker(context, invoice.id)
            return
        }
        if (!notificationsAllowed(context)) {
            cancelAlarm(context, invoice.id)
            clearScheduledMarker(context, invoice.id)
            return
        }
        val operation = alarmPendingIntent(context, invoice.id, dueDateText)
        val scheduledKey = "scheduled_${invoice.id}"
        if (!force && prefs.getString(scheduledKey, null) == dueDateText && alarmPendingIntentNoCreate(context, invoice.id) != null) return
        cancelAlarm(context, invoice.id)
        val triggerAt = invoiceReminderAtMillis(dueDateText, ZoneId.systemDefault()) ?: return
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        prefs.edit().putString(scheduledKey, dueDateText).apply()
        updateScheduledIds(context, invoice.id, include = true)
    }

    fun cancel(context: Context, invoiceId: String) {
        cancelAlarm(context, invoiceId)
        NotificationManagerCompat.from(context).cancel(notificationId(invoiceId))
        preferences(context).edit().remove("notified_$invoiceId").remove("scheduled_$invoiceId").apply()
        updateScheduledIds(context, invoiceId, include = false)
    }

    fun reconcile(context: Context, invoices: List<Invoice>, forceSchedule: Boolean = false) {
        val activeIds = invoices.filter { it.status != "Entwurf" && invoiceOutstandingCents(it) > 0 }.mapTo(mutableSetOf()) { it.id }
        val removedIds = preferences(context).getStringSet("scheduled_invoice_ids", emptySet()).orEmpty().toSet() - activeIds
        removedIds.forEach { cancel(context, it) }
        invoices.forEach { invoice ->
            if (invoice.id in activeIds) schedule(context, invoice, forceSchedule) else cancel(context, invoice.id)
        }
    }

    private fun cancelAlarm(context: Context, invoiceId: String) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmPendingIntentNoCreate(context, invoiceId)?.let(alarm::cancel)
    }

    private fun alarmPendingIntent(context: Context, invoiceId: String, dueDate: String?): PendingIntent {
        val intent = Intent(context, InvoiceReminderReceiver::class.java).setAction("de.kontoklar.app.INVOICE_PAYMENT_REMINDER")
            .putExtra(EXTRA_INVOICE_ID, invoiceId)
        dueDate?.let { intent.putExtra(EXTRA_DUE_DATE, it) }
        return PendingIntent.getBroadcast(
            context,
            invoiceId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun alarmPendingIntentNoCreate(context: Context, invoiceId: String): PendingIntent? {
        val intent = Intent(context, InvoiceReminderReceiver::class.java).setAction("de.kontoklar.app.INVOICE_PAYMENT_REMINDER")
            .putExtra(EXTRA_INVOICE_ID, invoiceId)
        return PendingIntent.getBroadcast(context, invoiceId.hashCode(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    }

    internal fun markNotified(context: Context, invoiceId: String, dueDate: String) {
        preferences(context).edit().putString("notified_$invoiceId", dueDate).remove("scheduled_$invoiceId").apply()
        updateScheduledIds(context, invoiceId, include = false)
    }

    internal fun alarmUnavailable(context: Context, invoiceId: String) {
        preferences(context).edit().remove("scheduled_$invoiceId").apply()
        updateScheduledIds(context, invoiceId, include = false)
    }

    private fun clearScheduledMarker(context: Context, invoiceId: String) {
        preferences(context).edit().remove("scheduled_$invoiceId").apply()
        updateScheduledIds(context, invoiceId, include = false)
    }

    private fun updateScheduledIds(context: Context, invoiceId: String, include: Boolean) {
        val preferences = preferences(context)
        val ids = preferences.getStringSet("scheduled_invoice_ids", emptySet()).orEmpty().toMutableSet()
        if (include) ids.add(invoiceId) else ids.remove(invoiceId)
        preferences.edit().putStringSet("scheduled_invoice_ids", ids).apply()
    }

    internal fun alreadyNotified(context: Context, invoiceId: String, dueDate: String): Boolean =
        preferences(context).getString("notified_$invoiceId", null) == dueDate

    internal fun notificationId(invoiceId: String): Int = invoiceId.hashCode()
    internal fun expectedDueDate(intent: Intent): String? = intent.getStringExtra(EXTRA_DUE_DATE)
    internal fun invoiceId(intent: Intent): String? = intent.getStringExtra(EXTRA_INVOICE_ID)
}

class InvoiceReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val invoiceId = InvoiceReminderScheduler.invoiceId(intent) ?: return
        val expectedDueDate = InvoiceReminderScheduler.expectedDueDate(intent) ?: return
        val invoice = LocalData(context).invoices().firstOrNull { it.id == invoiceId }
        if (invoice == null || invoice.status == "Entwurf" || invoiceOutstandingCents(invoice) == 0L || invoice.dueDate != expectedDueDate) {
            InvoiceReminderScheduler.cancel(context, invoiceId)
            return
        }
        if (InvoiceReminderScheduler.alreadyNotified(context, invoiceId, expectedDueDate)) return
        if (!InvoiceReminderScheduler.notificationsAllowed(context)) {
            InvoiceReminderScheduler.alarmUnavailable(context, invoiceId)
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(REMINDER_CHANNEL_ID, "Zahlungserinnerungen", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Hinweise zu überfälligen, versendeten Rechnungen"
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            InvoiceReminderScheduler.notificationId(invoiceId),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Zahlung bitte prüfen")
            .setContentText("Eine versendete Rechnung ist seit mindestens einem Tag überfällig.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Prüfe in KontoKlar den Zahlungseingang für deine überfällige Rechnung."))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(InvoiceReminderScheduler.notificationId(invoiceId), notification)
            InvoiceReminderScheduler.markNotified(context, invoiceId, expectedDueDate)
        } catch (_: SecurityException) {
            InvoiceReminderScheduler.alarmUnavailable(context, invoiceId)
        }
    }
}

class InvoiceReminderRebootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, "android.intent.action.TIME_SET", "android.intent.action.TIMEZONE_CHANGED", Intent.ACTION_MY_PACKAGE_REPLACED)) return
        InvoiceReminderScheduler.reconcile(context, LocalData(context).invoices(), forceSchedule = true)
    }
}
