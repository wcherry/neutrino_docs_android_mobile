package com.neutrino.docs

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class DocsApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        SyncWorker.schedule(this, graph.prefs.autoSync.value)

        // Foreground: drain the queue and top up retired keys; background: write the index now —
        // anything indexed since the last write would otherwise wait for a debounce the process may not see.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!graph.account.auth.isSignedIn) return
                if (graph.prefs.autoSync.value) graph.sync.requestSync()
                graph.scope.launch {
                    if (graph.account.keys.hasKey) runCatching { graph.account.keys.pullKeyFile() }.onSuccess { graph.account.keyringChanged() }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                if (graph.account.auth.isSignedIn) graph.search.flushSave()
            }
        })
    }
}

val Context.graph: AppGraph get() = (applicationContext as DocsApplication).graph

/**
 * Background upload and download: drains the queue, then refreshes cached copies that fell behind
 * the server. The system decides when it runs — an optimisation on top of foreground sync, never
 * what correctness depends on. The iOS `BGProcessingTask`.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        if (!graph.account.auth.isSignedIn) return Result.success()
        return withContext(Dispatchers.Main) {
            graph.sync.syncNow()
            graph.sync.refreshCachedDocuments()
            Result.success()
        }
    }

    companion object {
        private const val NAME = "docs-sync"

        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
