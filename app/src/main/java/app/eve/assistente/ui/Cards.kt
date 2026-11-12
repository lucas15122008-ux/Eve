package app.eve.assistente.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.eve.assistente.assistant.AgendaItem
import app.eve.assistente.assistant.EveCard

private val CardBg = Color(0xFF141A24)
private val Line = Color(0xFF222B38)
private val Accent = Color(0xFF5EE6D0)
private val Accent2 = Color(0xFF3FA9F5)
private val TextMain = Color(0xFFE8F1F5)
private val TextSoft = Color(0xFF8FA3B3)

/** Desenha a tela personalizada que a Eve pediu para mostrar. */
@Composable
fun EveCardView(card: EveCard, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CardBg)
            .padding(20.dp)
    ) {
        when (card) {
            is EveCard.Agenda -> {
                Header(card.title, "Sua agenda")
                ItemList(card.items, empty = "Nada marcado para esse dia.")
            }
            is EveCard.Reminders -> {
                Header("Lembretes", "O que a Eve vai te avisar")
                ItemList(card.items, empty = "Nenhum lembrete pendente.")
            }
            is EveCard.Done -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Check, contentDescription = null, tint = Color(0xFF0B0F17),
                        modifier = Modifier.size(32.dp).clip(CircleShape).background(Accent).padding(6.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(card.title, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(16.dp))
                ItemRow(card.item)
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
            Text("Fechar", color = Accent)
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Text(subtitle.uppercase(), color = TextSoft, fontSize = 12.sp, letterSpacing = 1.sp)
    Spacer(Modifier.height(4.dp))
    Text(title, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ItemList(items: List<AgendaItem>, empty: String) {
    if (items.isEmpty()) {
        Text(empty, color = TextSoft, fontSize = 16.sp, modifier = Modifier.padding(vertical = 12.dp))
        return
    }
    LazyColumn(
        Modifier.fillMaxWidth().height((items.size.coerceAtMost(6) * 64).dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items) { ItemRow(it) }
    }
}

@Composable
private fun ItemRow(item: AgendaItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Line)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.isReminder) Icons.Filled.Notifications else Icons.Filled.DateRange,
            contentDescription = if (item.isReminder) "Lembrete" else "Compromisso",
            tint = if (item.isReminder) Accent else Accent2,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = TextMain, fontSize = 16.sp, maxLines = 2)
            val sub = listOfNotNull(item.time, item.detail).joinToString(" · ")
            Text(sub, color = TextSoft, fontSize = 13.sp, maxLines = 1)
        }
    }
}
