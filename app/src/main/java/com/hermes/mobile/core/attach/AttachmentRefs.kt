package com.hermes.mobile.core.attach

import com.hermes.mobile.domain.model.ChatAttachment
import com.hermes.mobile.domain.model.mimeForName

/**
 * Finds files the PC is talking about inside ordinary message text.
 *
 * The agent has no file-attachment protocol; it says where a file is, in the
 * same formats the desktop already emits:
 *
 *  - `@image:D:/path/shot.png`   — how the gateway persists attached images
 *  - `@file:reports/q3.pdf`      — how `file.attach` refs are written back
 *  - `MEDIA:/abs/path/deck.pptx` — the desktop's "deliver this file" marker
 *
 * Inventing a sixth format would mean teaching the model something new and
 * breaking every transcript written before today. Reading the formats that
 * already exist means "send me the deck as a pptx" works with no server change:
 * the agent writes the file and names it, and the phone turns that name into a
 * real, openable, savable attachment card.
 *
 * A path that is quoted (backtick / single / double) round-trips: the gateway's
 * own `format_reference_value` quotes any value containing whitespace.
 */
object AttachmentRefs {

    /**
     * `@image:` / `@file:` / `MEDIA:` followed by a quoted or bare path.
     *
     * The bare-path branch stops at whitespace, which is why the quoted
     * branches come first — a Windows path with a space is the common case,
     * not the exotic one, and matching it greedily to end-of-line would
     * swallow the sentence after it.
     */
    private val REF = Regex(
        """(?:@(image|file)\s*:|(MEDIA)\s*:)\s*(?:`([^`\n]+)`|"([^"\n]+)"|'([^'\n]+)'|(\S+))""",
        RegexOption.IGNORE_CASE,
    )

    data class Ref(val path: String, val range: IntRange)

    /** Every file reference in [text], in document order. */
    fun scan(text: String): List<Ref> =
        REF.findAll(text).mapNotNull { m ->
            val raw = (3..6).firstNotNullOfOrNull { m.groupValues[it].takeIf(String::isNotBlank) }
                ?: return@mapNotNull null
            // Trailing sentence punctuation is not part of a bare path.
            val path = raw.trimEnd('.', ',', ';', ':', ')', ']', '"', '\'')
            if (path.isBlank()) null else Ref(path, m.range)
        }.toList()

    /**
     * Strip the reference markers from prose, leaving the sentence readable.
     *
     * The file is rendered as a card directly beneath, so repeating its full
     * Windows path inline is noise — and on a phone it is noise that wraps
     * across three lines.
     */
    fun strip(text: String): String =
        REF.replace(text, "").lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** Turn a PC-side path into a renderable attachment, keyed stably by [keyPrefix]. */
    fun toAttachment(path: String, keyPrefix: String, index: Int, autoDownload: Boolean = false): ChatAttachment {
        val name = path.replace('\\', '/').substringAfterLast('/').ifBlank { path }
        return ChatAttachment(
            id = "$keyPrefix-ref-$index",
            name = name,
            mimeType = mimeForName(name),
            sizeBytes = 0L,
            remotePath = path,
            autoDownload = autoDownload,
        )
    }

    /** Convenience: every reference in [text] as attachments. MEDIA: markers are auto-downloadable. */
    fun attachmentsIn(text: String, keyPrefix: String): List<ChatAttachment> =
        REF.findAll(text).mapNotNull { m ->
            val raw = (3..6).firstNotNullOfOrNull { m.groupValues[it].takeIf(String::isNotBlank) }
                ?: return@mapNotNull null
            // Trailing sentence punctuation is not part of a bare path.
            val path = raw.trimEnd('.', ',', ';', ':', ')', ']', '"', '\'')
            if (path.isBlank()) return@mapNotNull null
            toAttachment(path, keyPrefix, m.range.first, autoDownload = m.groupValues[2].isNotBlank())
        }
            .distinctBy { it.remotePath }
            .toList()
}
