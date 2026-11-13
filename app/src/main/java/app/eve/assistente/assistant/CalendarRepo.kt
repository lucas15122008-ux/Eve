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
    /** Id do evento na agenda do celular (a IA usa para alterar ou excluir). */
    val id: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String?,
    /** Compromisso que se repete (toda semana etc.): a Eve não mexe nesses. */
    val recurring: Boolean = false,
    /** false quando a agenda é só de leitura (feriados, agendas compartilhadas...). */
    val editable: Boolean = true,
)

/** Resultado de alterar ou excluir um evento. */
enum class EditResult { OK, NO_PERMISSION, NOT_FOUND, RECURRING, READ_ONLY, FAILED }

/** Lê, cria, altera e exclui eventos na agenda do celular. */
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
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.CALENDAR_ACCESS_LEVEL,
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
                    id = c.getLong(5),
                    title = c.getString(0)?.ifBlank { null } ?: "(sem título)",
                    startMillis = begin,
                    endMillis = end,
                    allDay = allDay,
                    location = c.getString(4)?.ifBlank { null },
                    recurring = !c.getString(6).isNullOrBlank(),
                    editable = c.getInt(7) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
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

    /** Acha um evento pelo id (para saber como ele está antes de alterar). */
    fun find(id: Long): CalendarEvent? {
        if (!canRead) return null
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Events.DELETED,
        )
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        return ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.getInt(7) == 1) return@use null
            val allDay = c.getInt(3) == 1
            // Igual em events(): dia inteiro vem em UTC.
            val offset = if (allDay) TimeZone.getDefault().getOffset(c.getLong(1)) else 0
            val start = c.getLong(1) - offset
            CalendarEvent(
                id = id,
                title = c.getString(0)?.ifBlank { null } ?: "(sem título)",
                startMillis = start,
                endMillis = if (c.isNull(2)) start + 3_600_000 else c.getLong(2) - offset,
                allDay = allDay,
                location = c.getString(4)?.ifBlank { null },
                recurring = !c.getString(5).isNullOrBlank(),
                editable = c.getInt(6) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
            )
        }
    }

    /** Confere se dá para mexer no evento; devolve o evento atual ou o motivo de não dar. */
    private fun checkEditable(id: Long): Pair<CalendarEvent?, EditResult> {
        if (!canWrite || !canRead) return null to EditResult.NO_PERMISSION
        val e = find(id) ?: return null to EditResult.NOT_FOUND
        if (e.recurring) return e to EditResult.RECURRING
        if (!e.editable) return e to EditResult.READ_ONLY
        return e to EditResult.OK
    }

    /**
     * Altera um evento. Só muda o que vier preenchido. Se mudar só o início, mantém a duração.
     * Devolve o resultado e o evento como ficou.
     */
    fun update(
        id: Long,
        title: String?,
        startMillis: Long?,
        endMillis: Long?,
        location: String?,
    ): Pair<EditResult, CalendarEvent?> {
        val (old, check) = checkEditable(id)
        if (check != EditResult.OK || old == null) return check to old
        val newStart = startMillis ?: old.startMillis
        // Dia inteiro que ganha hora vira 1 hora; senão mantém a duração que tinha.
        val duration = if (old.allDay) 3_600_000 else old.endMillis - old.startMillis
        var newEnd = endMillis ?: (newStart + duration)
        if (newEnd <= newStart) newEnd = newStart + 3_600_000
        val timeChanged = startMillis != null || endMillis != null
        val values = ContentValues().apply {
            if (!title.isNullOrBlank()) put(CalendarContract.Events.TITLE, title)
            if (location != null) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (timeChanged) {
                put(CalendarContract.Events.DTSTART, newStart)
                put(CalendarContract.Events.DTEND, newEnd)
                if (old.allDay) {
                    // Ganhou hora: deixa de ser "dia inteiro".
                    put(CalendarContract.Events.ALL_DAY, 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
            }
        }
        if (values.size() == 0) return EditResult.OK to old
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        val ok = runCatching { ctx.contentResolver.update(uri, values, null, null) > 0 }.getOrDefault(false)
        if (!ok) return EditResult.FAILED to old
        return EditResult.OK to (find(id) ?: old)
    }

    /** Exclui um evento. Devolve o resultado e o evento que foi excluído. */
    fun delete(id: Long): Pair<EditResult, CalendarEvent?> {
        val (old, check) = checkEditable(id)
        if (check != EditResult.OK || old == null) return check to old
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        val ok = runCatching { ctx.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
        return (if (ok) EditResult.OK else EditResult.FAILED) to old
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
