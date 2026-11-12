package app.eve.assistente.assistant

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone

/** Um compromisso da agenda do celular (que já sincroniza com o Google Agenda). */
data class CalendarEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String?,
)

/** Lê e cria eventos na agenda do celular. */
class CalendarRepo(context: Context) {
    private val ctx = context.applicationContext

    val canRead: Boolean
        get() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    val canWrite: Boolean
        get() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun events(fromMillis: Long, toMillis: Long, limit: Int = 50): List<CalendarEvent> {
        if (!canRead) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMillis)
            ContentUris.appendId(it, toMillis)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val result = mutableListOf<CalendarEvent>()
        ctx.contentResolver.query(
            uri, projection,
            "${CalendarContract.Instances.VISIBLE} = 1", null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext() && result.size < limit) {
                val allDay = c.getInt(3) == 1
                var begin = c.getLong(1)
                var end = c.getLong(2)
                if (allDay) {
                    // Eventos de dia inteiro vêm em UTC; ajusta para o fuso do celular.
                    val offset = TimeZone.getDefault().getOffset(begin)
                    begin -= offset
                    end -= offset
                }
                result += CalendarEvent(
                    title = c.getString(0)?.ifBlank { null } ?: "(sem título)",
                    startMillis = begin,
                    endMillis = end,
                    allDay = allDay,
                    location = c.getString(4)?.ifBlank { null },
                )
            }
        }
        return result
    }

    /** Cria um evento na agenda principal, com aviso 10 minutos antes. Devolve false se não conseguir. */
    fun insert(title: String, startMillis: Long, endMillis: Long, location: String?): Boolean {
        if (!canWrite) return false
        val calendarId = primaryCalendarId() ?: return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return false
        val eventId = ContentUris.parseId(uri)
        ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, 10)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
        return true
    }

    /** Escolhe a agenda principal: de preferência a da conta Google, onde dá para escrever. */
    private fun primaryCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}" +
            " AND ${CalendarContract.Calendars.VISIBLE} = 1"
        var best: Long? = null
        var bestScore = -1
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
            while (c.moveToNext()) {
                val score = (if (c.getInt(1) == 1) 2 else 0) + (if (c.getString(2) == "com.google") 1 else 0)
                if (score > bestScore) {
                    bestScore = score
                    best = c.getLong(0)
                }
            }
        }
        return best
    }
}
