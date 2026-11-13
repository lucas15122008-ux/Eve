package app.eve.assistente.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.eve.assistente.MainActivity
import app.eve.assistente.R
import org.json.JSONArray
import org.json.JSONObject

/** Um lembrete da Eve: um texto e a hora em que ela deve avisar. */
data class Reminder(val id: Int, val atMillis: Long, val text: String)

/** Guarda os lembretes no celular. */
class ReminderStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("eve_reminders", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<Reminder> {
        val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Reminder(o.getInt("id"), o.getLong("at"), o.getString("text"))
        }.sortedBy { it.atMillis }
    }

    @Synchronized
    fun add(atMillis: Long, text: String): Reminder {
        val list = all().toMutableList()
        val id = (list.maxOfOrNull { it.id } ?: 0) + 1
        val r = Reminder(id, atMillis, text)
        list += r
        save(list)
        return r
    }

    /** Muda a hora e/ou o texto de um lembrete. Devolve o lembrete como ficou, ou null se não achar. */
    @Synchronized
    fun update(id: Int, atMillis: Long?, text: String?): Reminder? {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return null
        val old = list[i]
        val r = old.copy(atMillis = atMillis ?: old.atMillis, text = text?.takeIf { it.isNotBlank() } ?: old.text)
        list[i] = r
        save(list)
        return r
    }

    @Synchronized
    fun remove(id: Int): Reminder? {
        val list = all().toMutableList()
        val r = list.firstOrNull { it.id == id } ?: return null
        list.remove(r)
        save(list)
        return r
    }

    private fun save(list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("at", it.atMillis).put("text", it.text)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "reminders"
    }
}

/** Agenda o aviso de cada lembrete com o despertador do Android. */
object ReminderScheduler {
    const val CHANNEL_ID = "lembretes"
    private const val EXTRA_ID = "reminder_id"
    private const val EXTRA_TEXT = "reminder_text"

    fun schedule(context: Context, r: Reminder) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context, r)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exactAllowed) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMillis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMillis, pi)
        }
    }

    fun cancel(context: Context, r: Reminder) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, r))
    }

    /** Depois que o celular reinicia, os alarmes somem: agenda tudo de novo. */
    fun rescheduleAll(context: Context) {
        val store = ReminderStore(context)
        val now = System.currentTimeMillis()
        store.all().forEach { r ->
            if (r.atMillis <= now) {
                notify(context, r.id, r.text)
                store.remove(r.id)
            } else {
                schedule(context, r)
            }
        }
    }

    private fun alarmIntent(context: Context, r: Reminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_ID, r.id)
            .putExtra(EXTRA_TEXT, r.text)
        return PendingIntent.getBroadcast(
            context, r.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lembretes da Eve", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avisos dos lembretes que você pediu para a Eve"
                }
            )
        }
    }

    fun notify(context: Context, id: Int, text: String) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Lembrete da Eve")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(1000 + id, n)
    }

    internal fun handleAlarm(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        notify(context, id, text)
        if (id >= 0) ReminderStore(context).remove(id)
    }
}

/** Recebe o "despertador" de um lembrete e mostra a notificação. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ReminderScheduler.handleAlarm(context, intent)
}

/** Reagenda os lembretes quando o celular liga. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ReminderScheduler.rescheduleAll(context)
    }
}
