package dev.supportmama

import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import dev.supportmama.internal.Chat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Asks the server for replies every [SupportMama.Config.backgroundCheckMinutes]
 * while a conversation is live, and posts a notification when one comes in.
 * Declared in the library's manifest; nothing for the host app to do.
 */
class ReplyCheckJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            val again = runCatching { Chat.get(this@ReplyCheckJob).checkInBackground() }.getOrDefault(true)
            if (!again) getSystemService(JobScheduler::class.java)?.cancel(params.jobId)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
