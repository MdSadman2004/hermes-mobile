package com.hermes.mobile.domain.model

import kotlinx.serialization.Serializable

/** One row in session.list (Phase 0 literal shape). */
@Serializable
data class SessionSummary(
    val id: String,
    val title: String = "",
    val preview: String = "",
    val startedAt: Double = 0.0,
    val messageCount: Int = 0,
    val source: String = "",
)


/**
 * A renderable component the agent produced.
 *
 * Lifecycle: the agent emits a ```tsx fence -> the client posts the source to
 * the PC artifact server -> esbuild returns a URL -> the WebView loads it.
 * [compiling] and [error] exist so the card can show an honest state at every
 * step instead of an empty box.
 */
@Serializable
data class ArtifactState(
    val id: String,
    val source: String,
    val lang: String = "tsx",
    val title: String = "",
    val url: String? = null,
    val compiling: Boolean = false,
    val error: String? = null,
    val compileMs: Int? = null,
)

/** Media/file kind, decided from MIME first and extension only as a fallback. */
enum class AttachmentKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, ARCHIVE, OTHER }

/**
 * A file living in the transcript.
 *
 * [localUri] is set for something the user just picked on the phone (renderable
 * before any upload finishes); [remotePath] is set once it exists on the PC —
 * either because we uploaded it, or because the agent produced it there.
 * [cachedUri] is a local copy of a REMOTE file, pulled down so images and video
 * can render without a second network round trip per frame.
 *
 * All three can be present; that is the normal state after a round trip.
 */
@Serializable
data class ChatAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localUri: String? = null,
    val remotePath: String? = null,
    val cachedUri: String? = null,
    val uploading: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
    /** True when the agent delivered this via a MEDIA: marker — auto-save to the phone. */
    val autoDownload: Boolean = false,
    /** Set once the file has been saved to the phone's Downloads, so it is not saved twice. */
    val downloadedToPhone: Boolean = false,
) {
    val kind: AttachmentKind
        get() = when {
            mimeType.startsWith("image/") -> AttachmentKind.IMAGE
            mimeType.startsWith("video/") -> AttachmentKind.VIDEO
            mimeType.startsWith("audio/") -> AttachmentKind.AUDIO
            mimeType == "application/pdf" -> AttachmentKind.PDF
            mimeType.startsWith("text/") ||
                mimeType.endsWith("json") ||
                mimeType.endsWith("xml") -> AttachmentKind.TEXT
            mimeType.contains("zip") || mimeType.contains("tar") ||
                mimeType.contains("compressed") -> AttachmentKind.ARCHIVE
            else -> AttachmentKind.OTHER
        }

    /** Best URI a renderer can point at right now, or null while it is in flight. */
    fun displayUri(): String? = cachedUri ?: localUri
}

/** MIME guessed from a filename — used for files named by the PC, which send no MIME. */
fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    "mp4" -> "video/mp4"
    "mov" -> "video/quicktime"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "m4a" -> "audio/mp4"
    "ogg" -> "audio/ogg"
    "flac" -> "audio/flac"
    "pdf" -> "application/pdf"
    "txt", "log", "csv" -> "text/plain"
    "md" -> "text/markdown"
    "json" -> "application/json"
    "xml" -> "application/xml"
    "html", "htm" -> "text/html"
    "zip" -> "application/zip"
    "tar" -> "application/x-tar"
    "gz", "tgz" -> "application/gzip"
    "7z" -> "application/x-7z-compressed"
    "rar" -> "application/vnd.rar"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    else -> "application/octet-stream"
}

/** A rendered unit of the cockpit transcript. */
sealed interface TranscriptItem {
    val key: String

    data class UserMessage(
        override val key: String,
        val text: String,
        val attachments: List<ChatAttachment> = emptyList(),
    ) : TranscriptItem

    data class AssistantMessage(
        override val key: String,
        val text: String,
        val streaming: Boolean = false,
        val usageContextPercent: Int? = null,
        val attachments: List<ChatAttachment> = emptyList(),
    ) : TranscriptItem

    data class ThinkingBlock(
        override val key: String,
        val text: String,
        val live: Boolean = false,
    ) : TranscriptItem

    data class ToolCallItem(
        override val key: String,
        val toolId: String,
        val name: String,
        val context: String?,
        val args: String?,
        val result: String?,
        val durationS: Double?,
        val running: Boolean,
        val approvalNote: String? = null,
        /** Wall-clock ms when this row was created — the activity log sorts on it. */
        val atMillis: Long = System.currentTimeMillis(),
    ) : TranscriptItem

    data class ApprovalCard(
        override val key: String,
        val sessionId: String,
        val command: String?,
        val description: String?,
        val choices: List<String>,
        /** Server allows "…and don't ask again this session". */
        val allowSession: Boolean = false,
        /** Server allows "…and remember permanently". */
        val allowPermanent: Boolean = false,
        val resolved: String? = null, // the choice made, once answered
    ) : TranscriptItem

    /** Output of a slash command run from the composer. */
    data class CommandOutput(
        override val key: String,
        val command: String,
        val output: String,
    ) : TranscriptItem

    data class StatusLine(override val key: String, val text: String) : TranscriptItem

    /** A component the agent wrote, compiled on the PC and rendered inline. */
    data class ArtifactItem(
        override val key: String,
        val artifact: ArtifactState,
    ) : TranscriptItem

    /** Files attached to a turn - by the user, or produced by the agent. */
    data class AttachmentGroup(
        override val key: String,
        val attachments: List<ChatAttachment>,
        val fromUser: Boolean,
    ) : TranscriptItem
}

/** Whether the agent is mid-turn on the active session. */
enum class TurnPhase { IDLE, RUNNING }

/**
 * What the agent is doing right now, in the user's language.
 *
 * This is the ONLY work-in-progress surface the conversation shows. Raw tool
 * invocations (`terminal`, `read_file`, their arguments and their stdout) are
 * machine detail: they belong in the Activity log, not between two sentences
 * of a reply. A chat transcript that interleaves shell commands is unreadable
 * on a 6" screen and buries the thing the user actually came back for.
 *
 * [detail] is a short, already-truncated hint (a filename, a URL host) — never
 * a command line.
 */
data class AgentActivity(
    val phase: String,
    val detail: String? = null,
    val running: Boolean = true,
    /** How many tool calls this turn has made, for the Activity badge. */
    val steps: Int = 0,
)

/**
 * Human phrase for a tool name.
 *
 * Deliberately verb-first and present tense, so the line reads as a status
 * ("Reading files…") rather than an API name. Unknown tools fall back to a
 * de-snake-cased form instead of a generic "working", because a wrong-but-
 * specific label is still more informative than a right-but-empty one.
 */
fun activityPhraseFor(tool: String): String {
    val t = tool.lowercase()
    return when {
        t.contains("terminal") || t.contains("shell") || t.contains("bash") ->
            "Running a command"
        t.contains("process") -> "Managing processes"
        t.startsWith("read") || t == "mcp__read_file" || t.contains("read_file") ->
            "Reading files"
        t.contains("write_file") || t.startsWith("write") -> "Writing a file"
        t.contains("patch") || t.contains("edit") -> "Editing code"
        t.contains("search_files") || t.contains("grep") || t.contains("glob") ->
            "Searching the project"
        t.contains("web_search") -> "Searching the web"
        t.contains("web_extract") || t.contains("fetch") || t.contains("crawl") ->
            "Reading a web page"
        t.contains("browser") -> "Driving the browser"
        t.contains("execute_code") || t.contains("python") -> "Running code"
        t.contains("delegate") || t.contains("subagent") || t.contains("swarm") ->
            "Delegating to subagents"
        t.contains("skill") -> "Loading a skill"
        t.contains("memory") || t.contains("context_notes") -> "Updating memory"
        t.contains("todo") -> "Updating the plan"
        t.contains("vision") || t.contains("image") -> "Looking at an image"
        t.contains("speech") || t.contains("audio") || t.contains("voice") ->
            "Working with audio"
        t.contains("cron") -> "Scheduling a job"
        t.contains("git") || t.contains("github") -> "Working with the repo"
        t.contains("clarify") -> "Asking you something"
        else -> tool.removePrefix("mcp__")
            .replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
    }
}
