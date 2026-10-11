package com.nmr.pinscheduler.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nmr.pinscheduler.api.ApiException
import com.nmr.pinscheduler.api.PinterestApi
import com.nmr.pinscheduler.data.PinStatus
import com.nmr.pinscheduler.data.PinStore
import com.nmr.pinscheduler.data.ScheduledPin
import java.util.concurrent.TimeUnit

object Scheduler {
    private fun name(id: String) = "pin-$id"

    fun schedule(ctx: Context, pin: ScheduledPin) {
        val delay = (pin.timeMillis - System.currentTimeMillis()).coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<PostWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(workDataOf("id" to pin.id))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(name(pin.id), ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(ctx: Context, id: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork(name(id))
    }

    /** Makes sure every pending pin has a job (e.g. after an app update). */
    fun ensureAll(ctx: Context) {
        PinStore.load(ctx)
        PinStore.pins.value.filter { it.status == PinStatus.PENDING || it.status == PinStatus.POSTING }
            .forEach { schedule(ctx, it.copy(status = PinStatus.PENDING)) }
    }
}

class PostWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getString("id") ?: return Result.failure()
        val pin = PinStore.get(ctx, id) ?: return Result.success()
        if (pin.status == PinStatus.POSTED) return Result.success()

        PinStore.update(ctx, id) { it.copy(status = PinStatus.POSTING, error = null) }
        return try {
            val pinId = PinterestApi(ctx).createPin(
                pin.boardId, pin.imagePath, pin.title, pin.description, pin.link, pin.altText
            )
            PinStore.update(ctx, id) { it.copy(status = PinStatus.POSTED, pinterestId = pinId, error = null) }
            Notifier.show(ctx, id.hashCode(), "Pin posted", pin.title.ifBlank { "Pin to ${pin.boardName}" })
            Result.success()
        } catch (e: ApiException) {
            if (e.transient && runAttemptCount < 5) {
                PinStore.update(ctx, id) { it.copy(status = PinStatus.PENDING, error = "Retrying: ${e.message}") }
                Result.retry()
            } else fail(ctx, pin, e.message ?: "Unknown error")
        } catch (e: Exception) {
            if (runAttemptCount < 5) {
                PinStore.update(ctx, id) { it.copy(status = PinStatus.PENDING, error = "Retrying: ${e.message}") }
                Result.retry()
            } else fail(ctx, pin, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(ctx: Context, pin: ScheduledPin, msg: String): Result {
        PinStore.update(ctx, pin.id) { it.copy(status = PinStatus.FAILED, error = msg) }
        Notifier.show(ctx, pin.id.hashCode(), "Pin failed to post", msg)
        return Result.failure()
    }
}

object Notifier {
    private const val CHANNEL = "posts"

    fun init(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Posting results", NotificationManager.IMPORTANCE_DEFAULT)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun show(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        init(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}
