package com.hisabkhata.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hisabkhata.R
import kotlin.math.min

/**
 * Keeps every character visible on one line at all font scales. The size never goes below
 * 11sp. If even 11sp cannot fit (e.g. Long.MAX_VALUE on a 320dp device with huge system
 * fonts), only glyph width is compressed; the actual full text remains available to TalkBack.
 */
@Composable
fun FitLedgerText(
    value: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    align: TextAlign = TextAlign.Start,
    bold: Boolean = false
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val startingSize = style.fontSize.value.takeIf { it.isFinite() && it > 0f }?.coerceAtLeast(11f) ?: 14f
        val baseStyle = style.copy(fontSize = startingSize.sp, letterSpacing = 0.sp,
            fontWeight = if (bold) FontWeight.Bold else style.fontWeight, textAlign = align)
        val naturalWidth = measurer.measure(AnnotatedString(value), style = baseStyle,
            constraints = Constraints(maxWidth = Constraints.Infinity), maxLines = 1, softWrap = false).size.width.coerceAtLeast(1)
        val fittedSize = (startingSize * min(1f, (widthPx - 1f).coerceAtLeast(1f) / naturalWidth)).coerceIn(11f, startingSize)
        val sizedStyle = baseStyle.copy(fontSize = fittedSize.sp)
        val naturalSizedWidth = measurer.measure(AnnotatedString(value), style = sizedStyle,
            constraints = Constraints(maxWidth = Constraints.Infinity), maxLines = 1, softWrap = false).size.width
        // A finite natural-width constraint avoids an infinite-width paragraph on Android.
        val childMaxWidth = (naturalSizedWidth.coerceIn(1, 32_000) + 4)
        // Measure text at its actual width, then transform the drawing into the column if
        // 11sp still does not fit. Unlike forcing a huge paragraph into a narrow Text, this
        // preserves the full laid-out glyphs and exposes the original text to accessibility.
        Layout(content = {
            Text(value, color = color, style = sizedStyle, maxLines = 1, softWrap = true, overflow = TextOverflow.Visible)
        }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
            val child = measurables.single().measure(Constraints(minWidth = childMaxWidth, maxWidth = childMaxWidth, maxHeight = constraints.maxHeight))
            val available = if (constraints.hasBoundedWidth) constraints.maxWidth else child.width
            val width = constraints.constrainWidth(available)
            val height = constraints.constrainHeight(child.height)
            val scale = min(1f, width.toFloat() / child.width.coerceAtLeast(1))
            val right = align == TextAlign.End || align == TextAlign.Right
            val x = if (right) width - child.width else 0
            layout(width, height) {
                child.placeWithLayer(x, 0) {
                    scaleX = scale
                    transformOrigin = TransformOrigin(if (right) 1f else 0f, 0f)
                }
            }
        }
    }
}

/** Four weighted columns always fit the available width; description has no header. */
@Composable
fun MobileLedgerHeader(labels: List<String>, modifier: Modifier = Modifier) {
    require(labels.size == 4)
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { index, label ->
                FitLedgerText(label, Modifier.weight(1f).padding(horizontal = 2.dp), style = MaterialTheme.typography.labelMedium,
                    align = if (index == 0) TextAlign.Start else TextAlign.End, bold = true)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = .55f))
    }
}

@Composable
fun MobileLedgerRow(
    date: String,
    first: String,
    second: String,
    balance: String,
    description: String,
    linked: Boolean,
    balanceColor: Color,
    alternating: Boolean,
    linkDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val openLabel = stringResource(R.string.open_entry)
    val background = if (alternating) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .30f) else MaterialTheme.colorScheme.surface
    Column(modifier.fillMaxWidth().heightIn(min = 48.dp).background(background)
        .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(date, first, second, balance).forEachIndexed { index, value ->
                FitLedgerText(value, Modifier.weight(1f).padding(horizontal = 2.dp),
                    color = if (index == 3) balanceColor else MaterialTheme.colorScheme.onSurface,
                    align = if (index == 0) TextAlign.Start else TextAlign.End)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            Text(description.ifBlank { stringResource(R.string.empty_description) }, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (linked) Icon(painterResource(R.drawable.ic_link), contentDescription = linkDescription,
                modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = .40f))
    }
}

@Preview(name = "Narrow phone", widthDp = 320, heightDp = 640, showBackground = true)
@Preview(name = "Normal phone", widthDp = 400, heightDp = 720, showBackground = true)
@Preview(name = "Largest font", widthDp = 320, heightDp = 800, fontScale = 2f, showBackground = true)
@Composable
private fun MobileLedgersPreview() {
    MaterialTheme {
        Column(Modifier.fillMaxWidth()) {
            MobileLedgerHeader(listOf(stringResource(R.string.column_date), stringResource(R.string.column_deposit_short),
                stringResource(R.string.column_cost), stringResource(R.string.column_total)))
            MobileLedgerRow("26.01.26", "150,000", "20,000", "-123,456.78", "Example Person A by account, rent and household expenses",
                false, MaterialTheme.colorScheme.error, false, stringResource(R.string.link_icon_description), {})
            MobileLedgerHeader(listOf(stringResource(R.string.column_date), stringResource(R.string.column_loan),
                stringResource(R.string.column_paid), stringResource(R.string.column_due)))
            MobileLedgerRow("20.01.26", "500,000", "300,000", "200,000", "Example transfer", true,
                MaterialTheme.colorScheme.error, true, stringResource(R.string.loan_cash_link_description), {})
        }
    }
}
