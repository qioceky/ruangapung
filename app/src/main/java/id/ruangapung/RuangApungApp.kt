package id.ruangapung

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Menangkap crash yang tidak tertangani dan menyimpannya ke penyimpanan
 * internal (filesDir/crash/). Tahan kartu status di Beranda untuk menyalin
 * log crash terakhir ke clipboard — berguna untuk diagnosis tanpa logcat.
 */
class RuangApungApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrash(throwable, thread.name)
            } catch (_: Exception) {
                // Jangan pernah crash di dalam crash handler.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrash(e: Throwable, threadName: String) {
        val dir = File(filesDir, "crash").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val trace = buildString {
            appendLine("Waktu: $ts")
            appendLine("Thread: $threadName")
            appendLine(e.toString())
            e.stackTrace.forEach { appendLine("    at $it") }
            var cause = e.cause
            var depth = 0
            while (cause != null && depth < 8) {
                appendLine("Caused by: $cause")
                cause.stackTrace.take(12).forEach { appendLine("    at $it") }
                cause = cause.cause
                depth++
            }
        }
        File(dir, "crash-$ts.txt").writeText(trace)
        File(dir, "crash-terakhir.txt").writeText(trace)
        dir.listFiles()
            ?.sortedByDescending { it.name }
            ?.drop(6)
            ?.forEach { runCatching { it.delete() } }
    }

    companion object {
        fun lastCrash(context: Context): String? {
            val f = File(File(context.filesDir, "crash"), "crash-terakhir.txt")
            return if (f.exists()) f.readText() else null
        }

        fun copyLastCrash(context: Context): Boolean {
            val trace = lastCrash(context) ?: return false
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ruangapung-crash", trace))
            return true
        }
    }
}
