package com.edo.app.ui.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads messages aloud through the platform [TextToSpeech] engine. One message at a time:
 * starting another replaces it, and [toggle] on the one being read stops it.
 */
class Speaker(context: Context) {
    private val _speaking = MutableStateFlow<String?>(null)
    /** Key of the message being read, or null. */
    val speaking: StateFlow<String?> = _speaking.asStateFlow()

    private var ready = false
    private var pending: Pair<String, String>? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) pending?.let { (key, text) -> start(key, text) }
        else _speaking.value = null
        pending = null
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId?.endsWith(LAST) == true) _speaking.value = null
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _speaking.value = null
            }
        })
    }

    fun toggle(key: String, text: String) {
        if (_speaking.value == key) stop() else {
            _speaking.value = key
            if (ready) start(key, text) else pending = key to text
        }
    }

    fun stop() {
        pending = null
        tts.stop()
        _speaking.value = null
    }

    private fun start(key: String, text: String) {
        val chunks = chunk(plainText(text), TextToSpeech.getMaxSpeechInputLength() - 1)
        tts.stop()
        chunks.forEachIndexed { i, c ->
            val id = if (i == chunks.lastIndex) "$key$LAST" else "$key#$i"
            tts.speak(c, TextToSpeech.QUEUE_ADD, null, id)
        }
        if (chunks.isEmpty()) _speaking.value = null
    }

    private companion object {
        const val LAST = "#last"
    }
}

/** Drops markdown syntax that would otherwise be read out ("asterisk asterisk"). */
internal fun plainText(markdown: String): String = markdown
    .replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " code block. ")
    .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
    .replace(Regex("(?m)^\\s{0,3}(#{1,6}|>|[-*+]|\\d+\\.)\\s+"), "")
    .replace(Regex("[*_`~|]+"), "")
    .replace(Regex("https?://\\S+"), "link")
    .trim()

/** Splits on paragraph, then sentence, then hard-cuts so each piece fits the engine's input limit. */
internal fun chunk(text: String, max: Int): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    fun flush() { if (cur.isNotBlank()) out.add(cur.toString().trim()); cur.clear() }
    for (para in text.split(Regex("\n{1,}"))) {
        for (piece in para.split(Regex("(?<=[.!?])\\s+")).flatMap { it.chunked(max) }) {
            if (cur.length + piece.length + 1 > max) flush()
            cur.append(piece).append(' ')
        }
        cur.append('\n')
    }
    flush()
    return out
}
