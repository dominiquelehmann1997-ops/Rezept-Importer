package de.dml.rezeptimporter.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.dml.rezeptimporter.dashboard.DashboardClient
import de.dml.rezeptimporter.dashboard.JobResult
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.draft.DraftStore
import de.dml.rezeptimporter.notify.AndroidNotifier
import de.dml.rezeptimporter.notify.ImportNotifier
import de.dml.rezeptimporter.settings.AppSettings
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Was nach einem Blick auf den Job zu tun ist. */
sealed interface ImportOutcome {
    data class Done(val draft: RecipeDraft) : ImportOutcome
    data class Failed(val message: String) : ImportOutcome
    data object Unclear : ImportOutcome
    data object KeepWaiting : ImportOutcome
}

/** Reine Entscheidung, getrennt vom Worker, damit sie ohne Android testbar ist. */
fun outcomeOf(job: JobResult, expired: Boolean): ImportOutcome = when (job) {
    is JobResult.Done -> ImportOutcome.Done(job.draft)
    is JobResult.Failed -> ImportOutcome.Failed(job.message)
    JobResult.Gone -> ImportOutcome.Unclear
    JobResult.Pending -> if (expired) ImportOutcome.Unclear else ImportOutcome.KeepWaiting
}

private const val POLL_INTERVAL_MS = 5_000L
private const val MAX_WAIT_MS = 300_000L

/**
 * Die Verfolgungsschleife selbst — ohne Android, damit sie testbar ist: Uhr,
 * Warten, Abfrage und die beiden Mitspieler kommen von außen. Der Worker baut
 * die echten Ausprägungen und reicht sie herein.
 */
suspend fun trackImport(
    jobId: String,
    drafts: DraftStore,
    notifier: ImportNotifier,
    now: () -> Long = System::currentTimeMillis,
    sleep: suspend () -> Unit = { delay(POLL_INTERVAL_MS) },
    poll: suspend () -> JobResult,
) {
    val deadline = now() + MAX_WAIT_MS
    while (true) {
        // Ein einzelner fehlgeschlagener Blick (Netz kurz weg, Tunnel zickt)
        // darf den Import nicht abschießen — der Job läuft serverseitig weiter.
        val job = runCatching { poll() }.getOrDefault(JobResult.Pending)

        when (val outcome = outcomeOf(job, expired = now() > deadline)) {
            is ImportOutcome.Done -> {
                // Erst ablegen, dann melden: danach ist die Benachrichtigung
                // unabhängig davon, ob der Job auf dem Server noch existiert.
                drafts.put(jobId, outcome.draft)
                notifier.done(jobId, outcome.draft.name)
                return
            }
            is ImportOutcome.Failed -> {
                notifier.failed(jobId, outcome.message)
                return
            }
            ImportOutcome.Unclear -> {
                notifier.unclear(jobId)
                return
            }
            ImportOutcome.KeepWaiting -> sleep()
        }
    }
}

class ImportStatusWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val client = DashboardClient(
            baseUrl = inputData.getString(KEY_BASE_URL).orEmpty(),
            token = inputData.getString(KEY_TOKEN).orEmpty(),
            cfClientId = inputData.getString(KEY_CF_ID).orEmpty(),
            cfClientSecret = inputData.getString(KEY_CF_SECRET).orEmpty(),
            client = OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(),
        )
        trackImport(
            jobId = jobId,
            drafts = DraftStore.of(applicationContext),
            notifier = AndroidNotifier(applicationContext),
            poll = { client.pollJob(jobId) },
        )
        return Result.success()
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "token"
        const val KEY_CF_ID = "cf_id"
        const val KEY_CF_SECRET = "cf_secret"

        fun enqueue(context: Context, jobId: String, settings: AppSettings) {
            val request = OneTimeWorkRequestBuilder<ImportStatusWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(
                    Data.Builder()
                        .putString(KEY_JOB_ID, jobId)
                        .putString(KEY_BASE_URL, settings.dashboardUrl)
                        .putString(KEY_TOKEN, settings.importToken)
                        .putString(KEY_CF_ID, settings.cfClientId)
                        .putString(KEY_CF_SECRET, settings.cfClientSecret)
                        .build()
                )
                .build()
            // Eindeutiger Name je Job, sonst fasst WorkManager mehrere Importe
            // zusammen und nur einer wird verfolgt.
            WorkManager.getInstance(context)
                .enqueueUniqueWork("import-$jobId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
