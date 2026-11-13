package app.eve.assistente.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.eve.assistente.assistant.AgendaItem
import app.eve.assistente.assistant.EveCard
import app.eve.assistente.mercadolivre.SalesBar
import app.eve.assistente.mercadolivre.SalesSummary

private val CardBg = Color(0xFF141A24)
private val Line = Color(0xFF222B38)
private val Accent = Color(0xFF5EE6D0)
private val Accent2 = Color(0xFF3FA9F5)
private val TextMain = Color(0xFFE8F1F5)
private val TextSoft = Color(0xFF8FA3B3)
/** A cor da Eve, usada nas barras do gráfico (passa no teste de contraste com o fundo do cartão). */
private val Violet = Color(0xFF7C5CFF)

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
            is EveCard.Sales -> SalesCard(card.summary)
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

/** Vendas do Mercado Livre: total em destaque, gráfico de barras e os mais vendidos. */
@Composable
private fun SalesCard(s: SalesSummary) {
    Header(s.periodLabel, if (s.sample) "Vendas de exemplo (não são reais)" else "Vendas no Mercado Livre")
    Text(SalesSummary.money(s.revenue), color = TextMain, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
    val ticket = if (s.count > 0) " · ticket médio ${SalesSummary.money(s.revenue / s.count)}" else ""
    Text("${s.count} ${if (s.count == 1) "venda" else "vendas"}$ticket", color = TextSoft, fontSize = 14.sp)
    if (s.partial) {
        Text("Eram muitas vendas: somei só as ${s.count} mais recentes.", color = TextSoft, fontSize = 13.sp)
    }
    if (s.count > 0 && s.bars.size > 1) {
        Spacer(Modifier.height(20.dp))
        BarChart(s.bars)
    }
    if (s.top.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Text("MAIS VENDIDOS", color = TextSoft, fontSize = 12.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.top.take(3).forEach { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Line)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${p.quantity} un.", color = TextSoft, fontSize = 14.sp, modifier = Modifier.width(52.dp))
                    Text(
                        p.title, color = TextMain, fontSize = 15.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(SalesSummary.money(p.revenue), color = TextSoft, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * Gráfico de barras de uma série só (por isso sem legenda: o título diz o que é).
 * Tocar numa barra mostra o valor dela; no começo aparece o melhor dia.
 */
@Composable
private fun BarChart(bars: List<SalesBar>) {
    var selected by remember(bars) {
        mutableIntStateOf(bars.indices.maxByOrNull { bars[it].revenue } ?: 0)
    }
    val sel = bars.getOrNull(selected)
    Text(
        sel?.let { "${it.label}: ${it.count} ${if (it.count == 1) "venda" else "vendas"} · ${SalesSummary.money(it.revenue)}" }.orEmpty(),
        color = TextMain, fontSize = 14.sp,
    )
    Spacer(Modifier.height(10.dp))
    val max = bars.maxOf { it.revenue }.takeIf { it > 0 } ?: 1.0
    val description = bars.joinToString("; ") { "${it.label}: ${it.count} vendas, ${SalesSummary.money(it.revenue)}" }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .semantics { contentDescription = "Gráfico de vendas. $description" }
            .pointerInput(bars) {
                detectTapGestures { pos ->
                    selected = (pos.x / (size.width.toFloat() / bars.size)).toInt().coerceIn(0, bars.lastIndex)
                }
            }
    ) {
        val slot = size.width / bars.size
        val gap = 2.dp.toPx()
        val barW = minOf(24.dp.toPx(), slot - gap)
        val radius = 4.dp.toPx()
        bars.forEachIndexed { i, b ->
            if (b.revenue <= 0) return@forEachIndexed
            val h = maxOf((b.revenue / max * size.height).toFloat(), 2.dp.toPx())
            val left = i * slot + (slot - barW) / 2f
            val r = minOf(radius, h, barW / 2f)
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = left, top = size.height - h, right = left + barW, bottom = size.height,
                        topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                        bottomRightCornerRadius = CornerRadius.Zero, bottomLeftCornerRadius = CornerRadius.Zero,
                    )
                )
            }
            drawPath(path, if (i == selected) Violet else Violet.copy(alpha = 0.5f))
        }
        // Linha de base, discreta.
        drawLine(Line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
    }
    Spacer(Modifier.height(6.dp))
    // Rótulos embaixo: todos quando cabem, senão um sim, outros não.
    val every = when {
        bars.size <= 8 -> 1
        bars.size <= 16 -> 2
        else -> 5
    }
    Row(Modifier.fillMaxWidth()) {
        bars.forEachIndexed { i, b ->
            Text(
                if (i % every == 0) b.label else "",
                color = if (i == selected) TextMain else TextSoft,
                fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
