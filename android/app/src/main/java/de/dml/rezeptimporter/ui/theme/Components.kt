package de.dml.rezeptimporter.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 4dp-Radius überall — keine Pills (DESIGN.md). */
val ArcaneShape = RoundedCornerShape(4.dp)

/** MTG-Card: 1dp Border, harter 3dp-Offset-Schatten ohne Blur, 16dp Padding. */
@Composable
fun ArcaneCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.padding(end = 3.dp, bottom = 3.dp)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(3.dp, 3.dp)
                .background(MaterialTheme.colorScheme.outline, ArcaneShape)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, ArcaneShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, ArcaneShape)
                .padding(16.dp),
            content = content,
        )
    }
}

/** Primary Action "Cast Spell": gefüllt Mana Violet. */
@Composable
fun ArcanePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ArcaneShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) { Text(text) }
}

/** Secondary Action "Scroll": transparent mit Border. */
@Composable
fun ArcaneSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = ArcaneShape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) { Text(text) }
}

/** Mono-Tag neben Headings, z. B. [FOTOS: 2]. */
@Composable
fun ArcaneTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = SpaceMono),
        color = color,
    )
}

/** Zelda-Style-Statbar: flache, harte Füllung im Rahmen — kein Verlauf, kein Blur. */
@Composable
fun ArcaneStatBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary,
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "statbar")
    Box(
        modifier
            .fillMaxWidth()
            .height(12.dp)
            .background(MaterialTheme.colorScheme.background, ArcaneShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, ArcaneShape),
    ) {
        if (animated > 0f) {
            Box(
                Modifier
                    .padding(2.dp)
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .background(color, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** Loot-Slots: gefüllte/leere Kästchen, z. B. ein Slot pro aufgenommenem Foto. */
@Composable
fun ArcaneSlotRow(filled: Int, modifier: Modifier = Modifier, minSlots: Int = 4) {
    val total = maxOf(minSlots, filled)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            val isFilled = i < filled
            Box(
                Modifier
                    .size(20.dp)
                    .background(
                        if (isFilled) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.background,
                        ArcaneShape,
                    )
                    .border(
                        1.dp,
                        if (isFilled) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.outline,
                        ArcaneShape,
                    ),
            )
        }
    }
}

/** Umrandeter Mono-Chip, z. B. [YOUTUBE] in der Portal-Liste. */
@Composable
fun ArcaneChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, ArcaneShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = SpaceMono),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Blinkender Terminal-Cursor hinter dem App-Titel. */
@Composable
fun BlinkingCursor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Text(
        "▌",
        modifier = modifier.alpha(alpha),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Input-Farben: flache Border, Hintergrund = Seitenhintergrund, Fokus = Mana Violet. */
@Composable
fun arcaneTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.background,
    unfocusedContainerColor = MaterialTheme.colorScheme.background,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
