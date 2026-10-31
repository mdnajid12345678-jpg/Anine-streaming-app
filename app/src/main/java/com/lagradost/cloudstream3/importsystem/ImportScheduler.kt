package com.lagradost.cloudstream3.importsystem

import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build.VERSION.SDK_INT
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream4.AppSettings
import java.util.concurrent.TimeUnit

const val IMPORT_WORK_NAME = "work_daily_import"
const val IMPORT_CHANNEL_ID = "cloudstream3.import"
const val IMPORT_CHANNEL_NAME = "Import"
const val IMPORT_CHANNEL_DESCRIPTION = "Background import job status"
const val IMPORT_NOTIFICATION_ID = 938713001

/**
 * Schedules the daily import.
 *
 * This is a real Android background job: it uses WorkManager with a network constraint and a
 * unique periodic work name, so re-enabling the toggle updates the same job instead of stacking
 * duplicates. WorkManager only runs while the device/app allows it (battery optimisation,
 * OEM restrictions, app standby), so "next run" is never guessed by the UI: only the last
 * *completed* run timestamp is persisted and shown.
 */
object ImportScheduler {

    fun setDailyEnabled(context: Context?, enabled: Boolean) {
        if (context == null) return
        val workManager = WorkManager.getInstance(context)

        if (!enabled) {
            workManager.cancelUniqueWork(IMPORT_WORK_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequest.Builder(
            ImportWorker::class.java,
            1,
            TimeUnit.DAYS,
        )
            .addTag(IMPORT_WORK_NAME)
            .setConstraints(constraints)
            .build()

        // KEEP preserves an already running/scheduled job; UPDATE replaces the definition when
        // the user changed scan settings, without creating a second daily job.
        workManager.enqueueUniquePeriodicWork(
            IMPORT_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun isScheduled(context: Context?): Boolean {
        if (context == null) return false
        return try {
            val infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(IMPORT_WORK_NAME).get()
            infos.any { !it.state.isFinished }
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * The scheduled import itself. It runs exactly the same [ImportEngine] as the manual button,
 * with the same persisted configuration, so a daily run is idempotent: canonical keys and the
 * database upserts are stable across runs.
 */
class ImportWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        context.createNotificationChannel(
            IMPORT_CHANNEL_ID,
            IMPORT_CHANNEL_NAME,
            IMPORT_CHANNEL_DESCRIPTION,
        )
        if (SDK_INT >= 29) {
            setForeground(
                ForegroundInfo(
                    IMPORT_NOTIFICATION_ID,
                    notificationBuilder(context).build(),
                    FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                ),
            )
        }

        val settings = AppSettings(context)
        val (config, error) = ImportManager.configFromSettings(settings)
        if (error != null) {
            // A misconfigured schedule is a permanent failure: retrying cannot help.
            return Result.failure()
        }

        val providers = ImportEngine.installedProviders().filter { it.name in config.providerNames }
        if (providers.isEmpty()) {
            // No provider installed right now (for example the plugin is disabled). Try again
            // on the next period instead of treating it as a completed import.
            return Result.retry()
        }

        return try {
            val callerKey = settings.importer.edgeFunctionKey.get().trim()
            val canCheckExistingTitles = BuildConfig.SUPABASE_CONFIGURED && callerKey.isNotEmpty()
            val existingTitles = if (canCheckExistingTitles) {
                val client = SupabaseImportClient(
                    functionUrl = BuildConfig.SUPABASE_IMPORT_FUNCTION_URL,
                    publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                    callerKey = callerKey,
                )
                client.existingProviderTitles(config.providerNames)
            } else {
                emptyMap()
            }
            val engine = ImportEngine(
                config = config,
                providers = providers,
                existingProviderTitles = existingTitles,
                initialWarnings = if (canCheckExistingTitles) emptyList() else listOf(
                    "Previously imported titles could not be checked for this scheduled scan",
                ),
            )
            val result = engine.run()

            if (config.uploadToSupabase && BuildConfig.SUPABASE_CONFIGURED) {
                val payload = buildPayload(config, result, settings)
                val client = SupabaseImportClient(
                    functionUrl = BuildConfig.SUPABASE_IMPORT_FUNCTION_URL,
                    publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                    callerKey = settings.importer.edgeFunctionKey.get(),
                )
                runCatching { client.upload(payload) }
                    .onFailure { return Result.retry() }
            }

            settings.importer.lastRunAt.set(System.currentTimeMillis())
            Result.success()
        } catch (t: Throwable) {
            if (isStopped) Result.failure() else Result.retry()
        }
    }

    private fun buildPayload(
        config: ImportConfig,
        result: ImportEngine.Result,
        settings: AppSettings,
    ): ImportUploadPayload {
        val contents = result.contents.values.toList()
        return ImportUploadPayload(
            job = ImportJobRecord(
                jobId = java.util.UUID.randomUUID().toString(),
                providerIds = config.providerNames.sorted(),
                filters = mapOf(
                    "categories" to config.categories.joinToString(",") { it.name },
                    "max_items" to config.maxItems.toString(),
                    "max_pages" to config.maxPages.toString(),
                    "year_from" to config.yearFrom?.toString().orEmpty(),
                    "year_to" to config.yearTo?.toString().orEmpty(),
                    "added_on_date" to config.addedOnDate.orEmpty(),
                    "source" to "android_scheduled_import",
                ),
                status = "completed",
                startedAt = result.progress.startedAt ?: System.currentTimeMillis(),
                finishedAt = result.progress.finishedAt ?: System.currentTimeMillis(),
                counters = mapOf(
                    "pages" to result.progress.catalogPagesScanned,
                    "discovered" to result.progress.itemsDiscovered,
                    "loaded" to result.progress.itemsLoaded,
                    "filtered" to result.progress.itemsFilteredOut,
                    "episodes" to result.progress.episodesProcessed,
                    "links_resolved" to result.progress.linksResolved,
                    "duplicates" to result.progress.duplicatesSkipped,
                    "failed" to result.progress.failed,
                ),
                errorSummary = result.failures.joinToString("; ") { "${it.stage}: ${it.message}" }
                    .take(500)
                    .ifBlank { null },
            ),
            providers = contents.flatMap { it.providerContents }
                .distinctBy { it.providerId }
                .map { ProviderRef(providerId = it.providerId, displayName = it.providerId) },
            contents = contents,
            duplicateCandidates = emptyList(),
            itemErrors = result.failures,
        )
    }

    private fun notificationBuilder(context: Context) = NotificationCompat.Builder(
        context,
        IMPORT_CHANNEL_ID,
    )
        .setColorized(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setAutoCancel(true)
        .setContentTitle(context.getString(R.string.category_import))
        .setContentText(context.getString(R.string.import_phase_discovering))
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setSmallIcon(R.drawable.ic_cloudstream_monochrome_big)
        .setColor(context.colorFromAttribute(R.attr.colorPrimary))
}
