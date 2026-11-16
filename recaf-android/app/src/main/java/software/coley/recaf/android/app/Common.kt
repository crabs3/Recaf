package software.coley.recaf.android.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Frame shared by every screen other than the chat: a title bar with a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenFrame(
	title: String,
	navigator: Navigator,
	subtitle: String? = null,
	actions: @Composable RowScope.() -> Unit = {},
	content: @Composable (PaddingValues) -> Unit,
) {
	Scaffold(
		topBar = {
			TopAppBar(
				title = {
					androidx.compose.foundation.layout.Column {
						Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.StartEllipsis)
						if (subtitle != null)
							Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1, overflow = TextOverflow.StartEllipsis)
					}
				},
				navigationIcon = {
					IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
				},
				actions = actions,
			)
		},
		content = content,
	)
}

/**
 * Shows source or listing text in a monospace font, scrollable in both directions.
 * Lines are laid out lazily so that classes with thousands of lines stay responsive.
 */
@Composable
fun CodeView(text: String, modifier: Modifier = Modifier, highlighted: Boolean = true) {
	val colors = codeColors()
	val lines = remember(text, highlighted, colors) {
		text.lines().map { if (highlighted) highlight(it, colors) else AnnotatedString(it) }
	}
	SelectionContainer(modifier) {
		Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
			LazyColumn(contentPadding = PaddingValues(12.dp)) {
				itemsIndexed(lines) { _, line ->
					Text(line, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp, softWrap = false)
				}
			}
		}
	}
}

/** Centered progress indicator for content that is being loaded. */
@Composable
fun Loading(modifier: Modifier = Modifier) {
	Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Centered message for when there is nothing to show. */
@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
	Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
		Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}
