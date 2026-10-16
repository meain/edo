package com.edo.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a file to another app via the system chooser. A `file://` source is copied into
 * `cacheDir/open/` first and served through [FileProvider], since other apps can't read
 * our workspace paths and `file://` URIs are blocked across apps.
 */
fun openExternally(context: Context, name: String, source: Uri) {
    val isCopy = source.scheme == "file"
    val uri = if (isCopy) {
        val from = File(source.path ?: return toast(context, "Can't open this file"))
        copyToCache(context, name) { it.outputStream().use { out -> from.inputStream().use { it.copyTo(out) } } }
    } else source
    // A SAF `content://` URI is the real file, so the other app can save straight back to it.
    // A cache copy can't, so it stays read-only.
    launchView(context, name, uri ?: return toast(context, "Can't open this file"), writable = !isCopy)
}

/** Same, for text that only exists in memory (e.g. a file attached to a chat message). */
fun openTextExternally(context: Context, name: String, content: String) {
    val uri = copyToCache(context, name) { it.writeText(content) }
        ?: return toast(context, "Can't open this file")
    launchView(context, name, uri)
}

private fun copyToCache(context: Context, name: String, write: (File) -> Unit): Uri? = runCatching {
    val dir = File(context.cacheDir, "open").apply { mkdirs() }
    val safe = name.substringAfterLast('/').replace(Regex("""[^\w.\- ]"""), "_").ifBlank { "file" }
    val file = File(dir, safe)
    write(file)
    FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
}.getOrNull()

private fun launchView(context: Context, name: String, uri: Uri, writable: Boolean = false) {
    val ext = name.substringAfterLast('.', "").lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (writable) addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "Open with").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }.onFailure { toast(context, "No app available") }
}

private fun toast(context: Context, msg: String) =
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
