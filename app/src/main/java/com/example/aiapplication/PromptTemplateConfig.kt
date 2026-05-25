package com.example.aiapplication

enum class RewriteToneType(val label: String) {
    FORMAL(label = "Formal"),
    CASUAL(label = "Casual"),
    FRIENDLY(label = "Friendly"),
    POLITE(label = "Polite"),
    ENTHUSIASTIC(label = "Enthusiastic"),
    CONCISE(label = "Concise"),
}

enum class SummarizationType(val label: String) {
    KEY_BULLET_POINT(label = "Key bullet points (3-5)"),
    SHORT_PARAGRAPH(label = "Short paragraph (1-2 sentences)"),
    CONCISE_SUMMARY(label = "Concise summary (~50 words)"),
    HEADLINE_TITLE(label = "Headline / title"),
    ONE_SENTENCE_SUMMARY(label = "One-sentence summary"),
}

enum class LanguageType(val label: String) {
    CPP(label = "C++"),
    JAVA(label = "Java"),
    JAVASCRIPT(label = "JavaScript"),
    KOTLIN(label = "Kotlin"),
    PYTHON(label = "Python"),
    SWIFT(label = "Swift"),
    TYPESCRIPT(label = "TypeScript"),
}

enum class InputEditorLabel(val label: String) {
    TONE(label = "Tone"),
    STYLE(label = "Style"),
    LANGUAGE(label = "Language"),
}

enum class PromptTemplateType(
    val label: String,
    val genFullPrompt: (userInput: String, options: Map<String, String>) -> String
) {
    FREE_FORM(
        label = "Free form",
        genFullPrompt = { userInput, _ -> userInput }
    ),
    REWRITE_TONE(
        label = "Rewrite tone",
        genFullPrompt = { userInput, options ->
            val tone = options[InputEditorLabel.TONE.label] ?: "formal"
            "Rewrite the following text using a ${tone.lowercase()} tone: $userInput"
        }
    ),
    SUMMARIZE_TEXT(
        label = "Summarize text",
        genFullPrompt = { userInput, options ->
            val style = options[InputEditorLabel.STYLE.label] ?: "key bullet points"
            "Please summarize the following in ${style.lowercase()}: $userInput"
        }
    ),
    CODE_SNIPPET(
        label = "Code snippet",
        genFullPrompt = { userInput, options ->
            val language = options[InputEditorLabel.LANGUAGE.label] ?: "Kotlin"
            "Write a $language code snippet to $userInput. Wrap the code in triple backticks."
        }
    )
}
