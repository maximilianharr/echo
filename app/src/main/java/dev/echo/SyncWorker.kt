package dev.echo

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

fun outbox(ctx: Context) = File(ctx.filesDir, "outbox")

/** Pushes one outbox file (repo-relative path) to GitHub, deleting it locally on success. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path = inputData.getString("path")!!
        val file = File(outbox(applicationContext), path)
        if (!file.exists()) return@withContext Result.success()
        val p = prefs(applicationContext)
        try {
            put(p.getString("repo", "")!!, p.getString("pat", "")!!, path, file.readBytes())
            file.delete()
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        }
    }

    companion object {
        fun enqueue(ctx: Context, path: String) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf("path" to path))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("sync")
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(path, ExistingWorkPolicy.KEEP, req)
        }
    }
}
