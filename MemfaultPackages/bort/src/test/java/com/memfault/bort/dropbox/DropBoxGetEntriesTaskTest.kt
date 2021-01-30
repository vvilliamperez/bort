package com.memfault.bort.dropbox

import android.os.DropBoxManager
import android.os.RemoteException
import android.system.StructStat
import com.github.michaelbull.result.Result
import com.memfault.bort.DropBoxSettings
import com.memfault.bort.ReporterClient
import com.memfault.bort.ReporterServiceConnection
import com.memfault.bort.ReporterServiceConnector
import com.memfault.bort.TaskResult
import com.memfault.bort.createMockServiceConnector
import com.memfault.bort.shared.DropBoxGetNextEntryRequest
import com.memfault.bort.shared.DropBoxGetNextEntryResponse
import com.memfault.bort.shared.DropBoxSetTagFilterRequest
import com.memfault.bort.shared.DropBoxSetTagFilterResponse
import com.memfault.bort.shared.ErrorResponseException
import com.memfault.bort.shared.VersionRequest
import com.memfault.bort.shared.VersionResponse
import com.memfault.bort.shared.result.failure
import com.memfault.bort.shared.result.success
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val TEST_SERVICE_VERSION = 3

class DropBoxGetEntriesTaskTest {
    lateinit var mockServiceConnection: ReporterServiceConnection
    lateinit var mockServiceConnector: ReporterServiceConnector
    @RelaxedMockK
    lateinit var mockEntryProcessor: EntryProcessor
    lateinit var task: DropBoxGetEntriesTask
    lateinit var lastProcessedEntryProvider: FakeLastProcessedEntryProvider

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        mockServiceConnection = mockk {
            coEvery {
                sendAndReceive(ofType(DropBoxSetTagFilterRequest::class))
            } returns Result.success(DropBoxSetTagFilterResponse())
            coEvery {
                sendAndReceive(ofType(VersionRequest::class))
            } returns Result.success(VersionResponse(TEST_SERVICE_VERSION))
        }
        val reporterClient = ReporterClient(mockServiceConnection, mockk())
        mockServiceConnector = createMockServiceConnector(reporterClient)
        lastProcessedEntryProvider = FakeLastProcessedEntryProvider(0)
        task = DropBoxGetEntriesTask(
            reporterServiceConnector = mockServiceConnector,
            lastProcessedEntryProvider = lastProcessedEntryProvider,
            entryProcessors = mapOf(TEST_TAG to mockEntryProcessor),
            settings = object : DropBoxSettings {
                override val dataSourceEnabled = true
            },
            retryDelayMillis = 0
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun mockGetNextEntryReponses(vararg entries: DropBoxManager.Entry?): () -> Unit {
        coEvery {
            mockServiceConnection.sendAndReceive(ofType(DropBoxGetNextEntryRequest::class))
        } returnsMany
            entries.map { Result.success(DropBoxGetNextEntryResponse(it)) }

        return {
            entries.forEach {
                it?.let { verify(exactly = 1) { it.close() } }
            }
        }
    }

    @Test
    fun failsTaskUponRemoteException() {
        coEvery { mockServiceConnection.sendAndReceive(any()) } throws RemoteException("Boom!")

        val result = runBlocking {
            task.doWork()
        }
        coVerify(exactly = 1) { mockServiceConnection.sendAndReceive(any()) }
        assertEquals(TaskResult.FAILURE, result)
    }

    @Test
    fun failsTaskIfSetTagFilterFails() {
        coEvery {
            mockServiceConnection.sendAndReceive(ofType(DropBoxSetTagFilterRequest::class))
        } returns Result.failure(ErrorResponseException("Can't do!"))

        val result = runBlocking {
            task.doWork()
        }
        coVerify(exactly = 1) {
            mockServiceConnection.sendAndReceive(ofType(DropBoxSetTagFilterRequest::class))
        }
        assertEquals(TaskResult.FAILURE, result)
    }

    @Test
    fun failsTaskIfGetNextEntryFails() {
        coEvery {
            mockServiceConnection.sendAndReceive(ofType(DropBoxGetNextEntryRequest::class))
        } returns Result.failure(ErrorResponseException("Can't do!"))

        val result = runBlocking {
            task.doWork()
        }
        coVerify(exactly = 1) {
            mockServiceConnection.sendAndReceive(ofType(DropBoxGetNextEntryRequest::class))
        }
        assertEquals(TaskResult.FAILURE, result)
    }

    @Test
    fun retriesOnceMoreAfterNullEntry() {
        mockGetNextEntryReponses(null)

        val result = runBlocking {
            task.doWork()
        }

        coVerify(exactly = 2) {
            mockServiceConnection.sendAndReceive(ofType(DropBoxGetNextEntryRequest::class))
        }
        assertEquals(TaskResult.SUCCESS, result)
    }

    @Test
    fun ignoreEntryProcessorExceptions() {
        val verifyCloseCalled = mockGetNextEntryReponses(
            mockEntry(10),
            mockEntry(20),
            null
        )
        coEvery { mockEntryProcessor.process(any()) } throws Exception("Processing failed!")

        val result = runBlocking {
            task.doWork()
        }

        coVerify(exactly = 4) {
            mockServiceConnection.sendAndReceive(ofType(DropBoxGetNextEntryRequest::class))
        }
        coVerify(exactly = 2) {
            mockEntryProcessor.process(any())
        }
        assertEquals(TaskResult.SUCCESS, result)
        assertEquals(20, lastProcessedEntryProvider.timeMillis)
        verifyCloseCalled()
    }

    @Test
    fun ignoreEntryTagWithNoMatchingProcessor() {
        val verifyCloseCalled = mockGetNextEntryReponses(
            mockEntry(10, tag_ = "unknown"),
            null
        )
        val result = runBlocking {
            task.doWork()
        }

        coVerify(exactly = 3) {
            mockServiceConnection.sendAndReceive(ofType(DropBoxGetNextEntryRequest::class))
        }
        coVerify(exactly = 0) {
            mockEntryProcessor.process(any())
        }
        assertEquals(TaskResult.SUCCESS, result)
        assertEquals(10, lastProcessedEntryProvider.timeMillis)
        verifyCloseCalled()
    }

    @Test
    fun ignoreEmptyEntry() {
        // fstat() is an extension function defined in DropBoxEntryExt.Kt
        mockkStatic("com.memfault.bort.dropbox.DropBoxEntryExtKt")
        val entry = mockEntry(10, tag_ = TEST_TAG)
        every {
            entry.fstat()
        } returns StructStat(
            0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0
        )
        val verifyCloseCalled = mockGetNextEntryReponses(entry, null)
        val result = runBlocking {
            task.doWork()
        }

        coVerify(exactly = 1) {
            mockEntryProcessor.process(any())
        }
        assertEquals(TaskResult.SUCCESS, result)
        assertEquals(10, lastProcessedEntryProvider.timeMillis)
        verifyCloseCalled()
    }

    private fun runAndAssertNoop() {
        val result = runBlocking {
            task.doWork()
        }
        assertEquals(TaskResult.SUCCESS, result)

        // There was nothing to do, so it must not connect to the reporter service at all:
        coVerify(exactly = 0) {
            mockServiceConnector.connect(any())
        }
        coVerify(exactly = 0) {
            mockServiceConnection.sendAndReceive(any())
        }
    }

    @Test
    fun emptyEntryProcessorsMap() {
        task = DropBoxGetEntriesTask(
            reporterServiceConnector = mockServiceConnector,
            lastProcessedEntryProvider = lastProcessedEntryProvider,
            entryProcessors = mapOf(),
            object : DropBoxSettings {
                override val dataSourceEnabled = true
            },
        )
        runAndAssertNoop()
    }

    @Test
    fun dataSourceDisabled() {
        task = DropBoxGetEntriesTask(
            reporterServiceConnector = mockServiceConnector,
            lastProcessedEntryProvider = lastProcessedEntryProvider,
            entryProcessors = mapOf(TEST_TAG to mockEntryProcessor),
            object : DropBoxSettings {
                override val dataSourceEnabled = false
            },
        )
        runAndAssertNoop()
    }
}
