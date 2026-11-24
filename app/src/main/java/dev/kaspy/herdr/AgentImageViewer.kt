package dev.kaspy.herdr

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun AgentImageViewer(
    preview: AgentFileImagePreview,
    downloading: Boolean,
    downloadMessage: String?,
    modifier: Modifier,
) {
    var zoom by remember { mutableIntStateOf(1) }
    val image = remember(preview.bitmap) { preview.bitmap.asImageBitmap() }
    val name = stringResource(R.string.agent_files_image_preview, preview.name)
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { zoom-- }, enabled = zoom > 1) { Text(stringResource(R.string.agent_files_zoom_out)) }
            TextButton(onClick = { zoom = 1 }, enabled = zoom > 1) { Text(stringResource(R.string.agent_files_zoom_fit)) }
            TextButton(onClick = { zoom++ }, enabled = zoom < 4) { Text(stringResource(R.string.agent_files_zoom_in)) }
        }
        val message = downloadMessage ?: if (downloading) stringResource(R.string.agent_files_downloading) else null
        if (message != null) {
            Text(message, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val viewportWidth = maxWidth
            val viewportHeight = maxHeight
            if (viewportWidth > 0.dp && viewportHeight > 0.dp) {
                val ratio = image.width.toFloat() / image.height
                val width = minOf(viewportWidth, viewportHeight * ratio)
                val height = width / ratio
                Box(
                    Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(maxOf(viewportWidth, width * zoom), maxOf(viewportHeight, height * zoom)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(image, contentDescription = name,
                            modifier = Modifier.size(width * zoom, height * zoom), contentScale = ContentScale.Fit)
                    }
                }
            }
        }
    }
}
