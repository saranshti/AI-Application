package com.example.aiapplication

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.CodeBlockStyle
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle

/** Composable function to display Markdown-formatted text. */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    smallFontSize: Boolean = false,
    textColor: Color = Color.White,
    linkColor: Color = Color(0xFF00A884),
) {
    val fontSize =
        if (smallFontSize) MaterialTheme.typography.bodyMedium.fontSize
        else MaterialTheme.typography.bodyLarge.fontSize
        
    CompositionLocalProvider {
        ProvideTextStyle(
            value =
            TextStyle(
                fontSize = fontSize,
                lineHeight = fontSize * if (smallFontSize) 1.4f else 1.5f,
                color = textColor,
                letterSpacing = 0.2.sp,
            ),
        ) {
            RichText(
                modifier = modifier,
                style =
                RichTextStyle(
                    codeBlockStyle =
                    CodeBlockStyle(
                        textStyle =
                        TextStyle(
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.4f,
                        )
                    ),
                    stringStyle =
                    RichTextStringStyle(linkStyle = SpanStyle(color = linkColor)),
                ),
            ) {
                Markdown(content = text)
            }
        }
    }
}
