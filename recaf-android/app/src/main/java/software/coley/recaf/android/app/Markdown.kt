package software.coley.recaf.android.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A block of a Markdown document. */
private sealed interface Block {
	data class Heading(val level: Int, val text: String) : Block
	data class Paragraph(val text: String) : Block
	data class Bullet(val marker: String, val indent: Int, val text: String) : Block
	data class Code(val text: String) : Block
	data object Rule : Block
}

/**
 * Renders the subset of Markdown the agent writes: headings, paragraphs, lists, fenced code, and inline bold,
 * italic and code. Tables are shown as preformatted text, which stays legible on a narrow screen.
 *
 * Parsing is line based and forgiving, since the text is rendered while it is still streaming in and is therefore
 * often incomplete.
 */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
	val blocks = remember(text) { parseBlocks(text) }
	val codeBackground = MaterialTheme.colorScheme.surfaceVariant
	val inlineCode = SpanStyle(fontFamily = Mono, background = codeBackground, fontSize = 13.sp)
	Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
		for (block in blocks) {
			when (block) {
				is Block.Heading -> Text(
					inline(block.text, inlineCode),
					style = when (block.level) {
						1 -> MaterialTheme.typography.titleLarge
						2 -> MaterialTheme.typography.titleMedium
						else -> MaterialTheme.typography.titleSmall
					},
					fontWeight = FontWeight.SemiBold,
				)
				is Block.Paragraph -> Text(inline(block.text, inlineCode), style = MaterialTheme.typography.bodyMedium)
				is Block.Bullet -> Row(Modifier.padding(start = (block.indent * 12).dp)) {
					Text(block.marker, Modifier.width(22.dp), style = MaterialTheme.typography.bodyMedium)
					Text(inline(block.text, inlineCode), style = MaterialTheme.typography.bodyMedium)
				}
				is Block.Code -> Text(
					block.text,
					Modifier
						.fillMaxWidth()
						.clip(RoundedCornerShape(8.dp))
						.background(codeBackground)
						.horizontalScroll(rememberScrollState())
						.padding(10.dp),
					fontFamily = Mono,
					fontSize = 12.sp,
					lineHeight = 16.sp,
				)
				Block.Rule -> androidx.compose.material3.HorizontalDivider()
			}
		}
	}
}

private val headingPattern = Regex("^(#{1,6})\\s+(.*)$")
private val bulletPattern = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")

private fun parseBlocks(text: String): List<Block> {
	val blocks = ArrayList<Block>()
	val paragraph = StringBuilder()
	fun flush() {
		if (paragraph.isNotBlank()) blocks.add(Block.Paragraph(paragraph.toString().trim()))
		paragraph.setLength(0)
	}

	val lines = text.lines()
	var i = 0
	while (i < lines.size) {
		val line = lines[i]
		val trimmed = line.trim()
		when {
			trimmed.startsWith("```") -> {
				flush()
				val code = StringBuilder()
				i++
				// An unterminated fence runs to the end: the closing fence may simply not have streamed in yet.
				while (i < lines.size && !lines[i].trim().startsWith("```")) {
					code.append(lines[i]).append('\n')
					i++
				}
				blocks.add(Block.Code(code.toString().trimEnd('\n')))
			}
			trimmed.startsWith("|") -> {
				flush()
				val table = StringBuilder()
				while (i < lines.size && lines[i].trim().startsWith("|")) {
					table.append(lines[i].trim()).append('\n')
					i++
				}
				i--
				blocks.add(Block.Code(table.toString().trimEnd('\n')))
			}
			trimmed.isEmpty() -> flush()
			trimmed.length >= 3 && trimmed.all { it == '-' || it == '*' || it == '_' } && trimmed.toSet().size == 1 -> {
				flush()
				blocks.add(Block.Rule)
			}
			headingPattern.matches(trimmed) -> {
				flush()
				val match = headingPattern.matchEntire(trimmed)!!
				blocks.add(Block.Heading(match.groupValues[1].length, match.groupValues[2]))
			}
			bulletPattern.matches(line) -> {
				flush()
				val match = bulletPattern.matchEntire(line)!!
				val marker = match.groupValues[2].let { if (it.first().isDigit()) it else "•" }
				blocks.add(Block.Bullet(marker, match.groupValues[1].length / 2, match.groupValues[3]))
			}
			else -> {
				if (paragraph.isNotEmpty()) paragraph.append(' ')
				paragraph.append(trimmed)
			}
		}
		i++
	}
	flush()
	return blocks
}

/** Applies inline code, bold and italic. Unmatched markers are kept as written. */
private fun inline(text: String, codeStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
	var i = 0
	while (i < text.length) {
		val c = text[i]
		if (c == '`') {
			val end = text.indexOf('`', i + 1)
			if (end > i) {
				withStyle(codeStyle) { append(text.substring(i + 1, end)) }
				i = end + 1
				continue
			}
		} else if (text.startsWith("**", i)) {
			val end = text.indexOf("**", i + 2)
			if (end > i) {
				withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(text.substring(i + 2, end), codeStyle)) }
				i = end + 2
				continue
			}
		} else if ((c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace()
			&& (i == 0 || !text[i - 1].isLetterOrDigit())
		) {
			val end = text.indexOf(c, i + 1)
			if (end > i + 1 && !text[end - 1].isWhitespace()) {
				withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
				i = end + 1
				continue
			}
		}
		append(c)
		i++
	}
}

private val javaKeywords = setOf(
	"abstract", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do",
	"double", "else", "enum", "extends", "final", "finally", "float", "for", "if", "implements", "import",
	"instanceof", "int", "interface", "long", "native", "new", "null", "package", "private", "protected", "public",
	"record", "return", "short", "static", "super", "switch", "synchronized", "this", "throw", "throws", "transient",
	"try", "void", "volatile", "while", "true", "false", "var",
)
private val token = Regex("//.*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|@[A-Za-z_][A-Za-z0-9_.]*|\\b\\d[\\dA-Fa-fxXlL._]*\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b")

/**
 * Colors one line of Java-like source or assembler text. Line based so that long listings can be rendered lazily,
 * which means block comments are only recognized by their leading asterisk.
 */
fun highlight(line: String, colors: CodeColors): AnnotatedString = buildAnnotatedString {
	val trimmed = line.trimStart()
	if (trimmed.startsWith("/*") || trimmed.startsWith("*")) {
		withStyle(SpanStyle(color = colors.comment)) { append(line) }
		return@buildAnnotatedString
	}
	var last = 0
	for (match in token.findAll(line)) {
		append(line.substring(last, match.range.first))
		val value = match.value
		val color: Color? = when {
			value.startsWith("//") -> colors.comment
			value.startsWith("\"") || value.startsWith("'") -> colors.string
			value.startsWith("@") -> colors.annotation
			value.first().isDigit() -> colors.number
			value in javaKeywords -> colors.keyword
			else -> null
		}
		if (color != null) withStyle(SpanStyle(color = color)) { append(value) } else append(value)
		last = match.range.last + 1
	}
	append(line.substring(last))
}
