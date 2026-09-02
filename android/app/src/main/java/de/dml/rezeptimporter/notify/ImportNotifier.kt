package de.dml.rezeptimporter.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.dml.rezeptimporter.R
import de.dml.rezeptimporter.ui.ShareActivity

/** Hinter dieser Schnittstelle steckt im Test eine Attrappe statt Android. */
interface ImportNotifier {
    fun done(jobId: String, name: String)
    fun failed(jobId: String, reason: String)
    fun unclear(jobId: String)
}

private const val CHANNEL_ID = "import"

class AndroidNotifier(private val context: Context) : ImportNotifier {

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Rezept-Import",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Id aus der Job-Id abgeleitet, damit mehrere Importe nebeneinander stehen
     * bleiben statt sich zu ersetzen.
     */
    private fun show(jobId: String, title: String, text: String, openPreview: Boolean) {
        ensureChannel()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.obsididine_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)

        if (openPreview) {
            val intent = Intent(context, ShareActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(ShareActivity.EXTRA_JOB_ID, jobId)
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    jobId.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }

        // Fehlt die Berechtigung, wirft `notify` eine SecurityException. Der Import
        // ist deswegen nicht gescheitert — das Rezept liegt im DraftStore und ist
        // über die App erreichbar. Also schlucken, nicht abstürzen.
        runCatching {
            NotificationManagerCompat.from(context).notify(jobId.hashCode(), builder.build())
        }
    }

    override fun done(jobId: String, name: String) =
        show(jobId, "Rezept fertig: $name", "Antippen zum Prüfen und Speichern", true)

    override fun failed(jobId: String, reason: String) =
        show(jobId, "Import fehlgeschlagen", reason, false)

    override fun unclear(jobId: String) =
        show(jobId, "Import unklar", "Bitte im Cockpit nachsehen oder erneut teilen", false)
}
