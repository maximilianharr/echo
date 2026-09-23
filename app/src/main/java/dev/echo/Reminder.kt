package dev.echo

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun alarm(ctx: Context) = PendingIntent.getBroadcast(
    ctx, 0, Intent(ctx, Reminder::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

/** Sets the next daily reminder alarm, or cancels it if the reminder is off. */
fun schedule(ctx: Context) {
    val am = ctx.getSystemService(AlarmManager::class.java)
    val p = prefs(ctx)
    if (!p.getBoolean("remind", false)) return am.cancel(alarm(ctx))
    val at = LocalTime.of(0, 0).plusMinutes(p.getInt("remindAt", 20 * 60).toLong())
    var next = LocalDateTime.of(LocalDate.now(), at)
    if (!next.isAfter(LocalDateTime.now())) next = next.plusDays(1)
    val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarm(ctx))
}

/** Fires at the reminder time (and on boot): notifies if there is no entry today, then reschedules. */
class Reminder : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        schedule(ctx)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) return
        val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        if (today in prefs(ctx).getStringSet("days", emptySet())!!) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("reminder", "Daily reminder", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            1,
            Notification.Builder(ctx, "reminder")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Time for your diary")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
