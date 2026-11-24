package dev.kaspy.herdr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val PocketBlack = Color.Black
internal val PocketInk = Color(0xFFF5F5F5)
internal val PocketSecondaryInk = Color(0xFFD0D0D0)
internal val PocketOutline = Color(0xFFB8B8B8)
internal val PocketDisabledInk = Color(0xFF777777)
internal val PocketSelected = Color(0xFF252525)
internal val PocketPanelShape = RoundedCornerShape(16.dp)
internal val PocketControlSize = 48.dp
internal val PocketGlyphSize = 24.dp
internal val PocketGlyphStroke = 1.9.dp

internal val PocketColorScheme = darkColorScheme(
    primary = PocketInk, onPrimary = PocketBlack,
    primaryContainer = PocketSelected, onPrimaryContainer = PocketInk,
    secondary = PocketInk, onSecondary = PocketBlack,
    secondaryContainer = PocketBlack, onSecondaryContainer = PocketInk,
    tertiary = PocketInk, onTertiary = PocketBlack,
    tertiaryContainer = PocketSelected, onTertiaryContainer = PocketInk,
    background = PocketBlack, onBackground = PocketInk,
    surface = PocketBlack, onSurface = PocketInk,
    surfaceVariant = PocketBlack, onSurfaceVariant = PocketSecondaryInk,
    surfaceContainerLowest = PocketBlack, surfaceContainerLow = PocketBlack,
    surfaceContainer = PocketBlack, surfaceContainerHigh = PocketBlack,
    surfaceContainerHighest = PocketBlack, surfaceBright = PocketBlack, surfaceDim = PocketBlack,
    surfaceTint = PocketBlack, outline = PocketOutline, outlineVariant = Color(0xFF555555),
    inverseSurface = PocketInk, inverseOnSurface = PocketBlack, inversePrimary = PocketBlack,
)

@Composable
internal fun PocketIconControl(
    label: String, onClick: () -> Unit, diameter: Dp = PocketControlSize,
    enabled: Boolean = true, modifier: Modifier = Modifier, emphasized: Boolean = false,
    content: @Composable (Color) -> Unit,
) {
    val ink = if (enabled) PocketInk else PocketDisabledInk
    Surface(
        onClick = onClick, enabled = enabled, shape = CircleShape, color = PocketBlack,
        contentColor = ink, border = BorderStroke(if (emphasized) 2.dp else 1.dp, if (enabled) PocketOutline else ink),
        modifier = modifier.size(diameter).semantics { contentDescription = label; role = Role.Button },
    ) {
        Box(contentAlignment = Alignment.Center) { content(ink) }
    }
}

@Composable
internal fun PocketMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismissRequest, shape = PocketPanelShape,
        containerColor = PocketBlack, tonalElevation = 0.dp, border = BorderStroke(1.dp, PocketOutline),
        content = content,
    )
}

@Composable
internal fun PocketDialog(
    onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest, confirmButton = confirmButton, dismissButton = dismissButton,
        title = title, text = text, shape = PocketPanelShape, containerColor = PocketBlack, tonalElevation = 0.dp,
        modifier = Modifier.border(1.dp, PocketOutline, PocketPanelShape),
    )
}

@Composable
internal fun pocketSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = PocketBlack, checkedTrackColor = PocketInk, checkedBorderColor = PocketInk,
    uncheckedThumbColor = PocketInk, uncheckedTrackColor = PocketBlack, uncheckedBorderColor = PocketOutline,
)
