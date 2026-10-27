package com.thermalbench.ui.common

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One option in a mutually exclusive row, such as the backend or compute unit.
 *
 * Material's filter chip marks the selected option with a faint tint and drops
 * its outline, which makes it look *less* emphatic than the outlined options
 * beside it; a CPU/GPU row read as if the wrong one were chosen. Here the
 * selected chip is the one that stands out: a dark outline and an inner shadow
 * so it reads as a button pressed into the surface, while unselected chips keep
 * only a light outline.
 */
@Composable
fun SelectionChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = FilterChipDefaults.shape
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        },
        shape = shape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = colors.secondaryContainer,
            selectedLabelColor = colors.onSecondaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = colors.outlineVariant,
            selectedBorderColor = colors.onSurface,
            borderWidth = UNSELECTED_BORDER,
            selectedBorderWidth = SELECTED_BORDER,
        ),
        modifier = if (selected && enabled) {
            modifier.innerShadow(shape, FilterChipDefaults.Height, SELECTED_BORDER, PRESSED_SHADOW, SHADOW_DEPTH)
        } else {
            modifier
        },
    )
}

/**
 * Darkens the inside of [shape] along its top and left edges, fading inward:
 * the shading a recessed surface gets from light falling from the top left.
 * Drawn over the content but confined to a thin band at the edges, so the
 * label in the middle is untouched.
 *
 * Material pads a chip out to a 48 dp touch target while the visible chip is
 * only [visibleHeight] tall and centred in it, so the shadow is drawn in that
 * centred band. Drawn over the whole layout, it lands in the padding above the
 * chip instead of inside it. The gradient starts at the inner edge of the
 * [border], since its darkest part would otherwise be hidden under it.
 */
private fun Modifier.innerShadow(shape: Shape, visibleHeight: Dp, border: Dp, color: Color, depth: Dp): Modifier =
    drawWithContent {
        drawContent()
        val chip = Size(size.width, visibleHeight.toPx().coerceAtMost(size.height))
        val inset = border.toPx()
        val edge = inset + depth.toPx()
        translate(top = (size.height - chip.height) / 2f) {
            val outline = Path().apply { addOutline(shape.createOutline(chip, layoutDirection, this@drawWithContent)) }
            clipPath(outline) {
                drawRect(Brush.verticalGradient(listOf(color, Color.Transparent), startY = inset, endY = edge))
                drawRect(
                    Brush.horizontalGradient(
                        listOf(color.copy(alpha = color.alpha * SIDE_SHADOW_RATIO), Color.Transparent),
                        startX = inset,
                        endX = inset + depth.toPx() * SIDE_SHADOW_RATIO,
                    ),
                    size = chip,
                )
            }
        }
    }

private val SELECTED_BORDER = 2.dp
private val UNSELECTED_BORDER = 1.dp
private val SHADOW_DEPTH = 7.dp
private val PRESSED_SHADOW = Color.Black.copy(alpha = 0.45f)

/** The side shadow is lighter and narrower than the top one, as for light from above. */
private const val SIDE_SHADOW_RATIO = 0.75f
