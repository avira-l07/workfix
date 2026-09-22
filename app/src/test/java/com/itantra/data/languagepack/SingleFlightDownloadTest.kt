package com.itantra.data.languagepack

import com.itantra.domain.model.LanguageCode
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightDownloadTest {

    /**
     * Replicates the exact atomic single-flight download pattern implemented
     * in RealLanguagePackRepository for Patch 4.
     */
    private class SingleFlightDownloadManager {
        val downloadJobs = mutableMapOf<LanguageCode, Job>()
        val downloadMutex = Mutex()
        val executionCounter = AtomicInteger(0)

        suspend fun startDownload(code: LanguageCode, executionDelayMs: Long = 100L): Job? {
            val jobToStart: Job? = downloadMutex.withLock {
                val existing = downloadJobs[code]
                if (existing != null && !existing.isCompleted) {
                    return@withLock null
                }

                lateinit var createdJob: Job
                createdJob = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                    try {
                        executionCounter.incrementAndGet()
                        delay(executionDelayMs)
                    } finally {
                        downloadMutex.withLock {
                            if (downloadJobs[code] === createdJob) {
                                downloadJobs.remove(code)
                            }
                        }
                    }
                }
                downloadJobs[code] = createdJob
                createdJob
            }

            jobToStart?.start()
            return jobToStart
        }
    }

    @Test
    fun testConcurrentDownloadsTriggerOnlyOneJob() = runBlocking {
        val manager = SingleFlightDownloadManager()
        val code = LanguageCode.HINDI

        // Launch two concurrent startDownload calls for the same language
        val job1Deferred = async { manager.startDownload(code, executionDelayMs = 200L) }
        val job2Deferred = async { manager.startDownload(code, executionDelayMs = 200L) }

        val job1 = job1Deferred.await()
        val job2 = job2Deferred.await()

        // Exactly one call creates a job; the concurrent duplicate returns null
        val createdCount = listOfNotNull(job1, job2).size
        assertEquals("Patch 4: Exactly one underlying download job must be created", 1, createdCount)

        // Wait for the active job to finish
        val activeJob = job1 ?: job2
        assertNotNull(activeJob)
        activeJob?.join()

        assertEquals("Underlying execution must run exactly once", 1, manager.executionCounter.get())
        assertTrue("downloadJobs map must be cleaned up after completion", manager.downloadJobs.isEmpty())
    }

    @Test
    fun testSequentialDownloadsAfterCompletionCanSucceed() = runBlocking {
        val manager = SingleFlightDownloadManager()
        val code = LanguageCode.ENGLISH

        // First download runs to completion
        val job1 = manager.startDownload(code, executionDelayMs = 50L)
        assertNotNull(job1)
        job1?.join()
        assertEquals(1, manager.executionCounter.get())

        // Subsequent download for the same code now succeeds because previous finished
        val job2 = manager.startDownload(code, executionDelayMs = 50L)
        assertNotNull(job2)
        job2?.join()
        assertEquals(2, manager.executionCounter.get())
    }
}
