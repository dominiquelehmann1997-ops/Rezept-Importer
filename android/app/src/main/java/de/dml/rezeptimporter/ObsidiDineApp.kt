package de.dml.rezeptimporter

import android.app.Application
import java.io.File

class ObsidiDineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Ohne adb-Zugriff ist Logcat unerreichbar — Stacktrace des letzten
        // Absturzes landet in einer Datei und wird beim nächsten Start angezeigt.
        val crashFile = File(filesDir, "last-crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { crashFile.writeText(e.stackTraceToString()) }
            previous?.uncaughtException(thread, e)
        }
    }
}
