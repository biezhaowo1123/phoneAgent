package com.phoneagent.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight Markdown renderer for Compose.
 * Supports: headers, bold, italic, strikethrough, inline code, code blocks,
 * bullet/numbered lists, blockquotes, horizontal rules, and links.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.onSurface,
    linkColor: Color = MaterialTheme.colorScheme.primary,
    codeBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }

    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.CodeBlock -> {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = codeBackground,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                if (block.language.isNotEmpty()) {
                                    Text(
                                        block.language,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = baseColor.copy(alpha = 0.5f)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                                Text(
                                    block.code,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                    ),
                                    color = baseColor,
                                    modifier = Modifier.horizontalScroll(rememberScrollState())
                                )
                            }
                        }
                    }
                    is MdBlock.HorizontalRule -> {
                        androidx.compose.material3.Divider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = baseColor.copy(alpha = 0.3f)
                        )
                    }
                    is MdBlock.TextBlock -> {
                        val annotated = buildInlineAnnotated(block.text, baseColor, linkColor)
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            3 -> MaterialTheme.typography.titleMedium
                            4 -> MaterialTheme.typography.titleSmall
                            else -> MaterialTheme.typography.bodyMedium
                        }
                        val bulletPrefix = when {
                            block.bulletIndent >= 0 -> "  ".repeat(block.bulletIndent) + "• "
                            block.orderedNum > 0 -> "${block.orderedNum}. "
                            block.isQuote -> "▎ "
                            else -> ""
                        }

                        if (bulletPrefix.isNotEmpty()) {
                            val combined = buildAnnotatedString {
                                append(bulletPrefix)
                                append(annotated)
                            }
                            Text(
                                combined,
                                style = style.copy(
                                    fontStyle = if (block.isQuote) FontStyle.Italic else FontStyle.Normal,
                                    color = if (block.isQuote) baseColor.copy(alpha = 0.8f) else baseColor
                                ),
                            )
                        } else {
                            Text(annotated, style = style)
                        }
                    }
                }
            }
        }
    }
}

// ========================================================================
//  Parsing
// ========================================================================

private sealed class MdBlock {
    data class TextBlock(
        val text: String,
        val level: Int = 0,          // 0=normal, 1-4=header
        val bulletIndent: Int = -1,  // -1 = not a bullet
        val orderedNum: Int = 0,
        val isQuote: Boolean = false,
    ) : MdBlock()

    data class CodeBlock(val code: String, val language: String) : MdBlock()
    data object HorizontalRule : MdBlock()
}

private fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Code block: ```
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MdBlock.CodeBlock(codeLines.joinToString("\n"), lang))
            i++ // skip closing ```
            continue
        }

        // Horizontal rule
        if (line.trim().matches(Regex("^[-*_]{3,}$"))) {
            blocks.add(MdBlock.HorizontalRule)
            i++
            continue
        }

        // Headers
        val headerMatch = Regex("^(#{1,4})\\s+(.*)").find(line)
        if (headerMatch != null) {
            val level = headerMatch.groupValues[1].length
            blocks.add(MdBlock.TextBlock(headerMatch.groupValues[2], level = level))
            i++
            continue
        }

        // Blockquote
        if (line.trimStart().startsWith("> ")) {
            blocks.add(MdBlock.TextBlock(line.trimStart().removePrefix("> "), isQuote = true))
            i++
            continue
        }

        // Unordered list
        val bulletMatch = Regex("^(\\s*)[-*+]\\s+(.*)").find(line)
        if (bulletMatch != null) {
            val indent = bulletMatch.groupValues[1].length / 2
            blocks.add(MdBlock.TextBlock(bulletMatch.groupValues[2], bulletIndent = indent))
            i++
            continue
        }

        // Ordered list
        val orderedMatch = Regex("^(\\d+)\\.\\s+(.*)").find(line)
        if (orderedMatch != null) {
            blocks.add(MdBlock.TextBlock(
                orderedMatch.groupValues[2],
                orderedNum = orderedMatch.groupValues[1].toIntOrNull() ?: 1
            ))
            i++
            continue
        }

        // Regular text (skip empty lines)
        if (line.isNotBlank()) {
            blocks.add(MdBlock.TextBlock(line))
        }
        i++
    }

    return blocks
}

// ========================================================================
//  Inline formatting
// ========================================================================

private fun buildInlineAnnotated(
    text: String,
    baseColor: Color,
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    var remaining = text
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        background = baseColor.copy(alpha = 0.1f)
    )

    while (remaining.isNotEmpty()) {
        // Inline code: `code`
        val codeMatch = Regex("`([^`]+)`").find(remaining)
        // Bold + italic: ***text*** or ___text___
        val boldItalicMatch = Regex("\\*{3}(.+?)\\*{3}|_{3}(.+?)_{3}").find(remaining)
        // Bold: **text** or __text__
        val boldMatch = Regex("\\*{2}(.+?)\\*{2}|_{2}(.+?)_{2}").find(remaining)
        // Italic: *text* or _text_
        val italicMatch = Regex("(?<![*])\\*([^*]+)\\*(?![*])|(?<!_)_([^_]+)_(?!_)").find(remaining)
        // Strikethrough: ~~text~~
        val strikeMatch = Regex("~~(.+?)~~").find(remaining)
        // Link: [text](url)
        val linkMatch = Regex("\\[([^]]+)]\\(([^)]+)\\)").find(remaining)

        // Find earliest match
        val matches = listOfNotNull(
            codeMatch?.let { it.range.first to "code" },
            boldItalicMatch?.let { it.range.first to "bolditalic" },
            boldMatch?.let { it.range.first to "bold" },
            italicMatch?.let { it.range.first to "italic" },
            strikeMatch?.let { it.range.first to "strike" },
            linkMatch?.let { it.range.first to "link" },
        ).sortedBy { it.first }

        if (matches.isEmpty()) {
            withStyle(SpanStyle(color = baseColor)) { append(remaining) }
            break
        }

        val (pos, type) = matches.first()

        // Text before the match
        if (pos > 0) {
            withStyle(SpanStyle(color = baseColor)) { append(remaining.substring(0, pos)) }
        }

        when (type) {
            "code" -> {
                val m = codeMatch!!
                withStyle(codeStyle) { append(m.groupValues[1]) }
                remaining = remaining.substring(m.range.last + 1)
            }
            "bolditalic" -> {
                val m = boldItalicMatch!!
                val content = m.groupValues[1].ifEmpty { m.groupValues[2] }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = baseColor)) {
                    append(content)
                }
                remaining = remaining.substring(m.range.last + 1)
            }
            "bold" -> {
                val m = boldMatch!!
                val content = m.groupValues[1].ifEmpty { m.groupValues[2] }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) { append(content) }
                remaining = remaining.substring(m.range.last + 1)
            }
            "italic" -> {
                val m = italicMatch!!
                val content = m.groupValues[1].ifEmpty { m.groupValues[2] }
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor)) { append(content) }
                remaining = remaining.substring(m.range.last + 1)
            }
            "strike" -> {
                val m = strikeMatch!!
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = baseColor)) {
                    append(m.groupValues[1])
                }
                remaining = remaining.substring(m.range.last + 1)
            }
            "link" -> {
                val m = linkMatch!!
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(m.groupValues[1])
                }
                remaining = remaining.substring(m.range.last + 1)
            }
            else -> {
                withStyle(SpanStyle(color = baseColor)) { append(remaining.first().toString()) }
                remaining = remaining.drop(1)
            }
        }
    }
}
