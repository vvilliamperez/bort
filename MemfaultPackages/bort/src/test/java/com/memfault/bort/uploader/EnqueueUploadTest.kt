package com.memfault.bort.uploader

import android.content.Context
import com.memfault.bort.BugReportFileUploadPayload
import com.memfault.bort.FakeCombinedTimeProvider
import com.memfault.bort.clientserver.CachedClientServerMode
import com.memfault.bort.clientserver.LinkedDeviceFileSender
import com.memfault.bort.clientserver.MarFileHoldingArea
import com.memfault.bort.clientserver.MarFileWriter
import com.memfault.bort.ingress.IngressService
import com.memfault.bort.shared.CLIENT_SERVER_FILE_UPLOAD_DROPBOX_TAG
import com.memfault.bort.shared.ClientServerMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import org.junit.jupiter.api.Test
import retrofit2.Call

internal class EnqueueUploadTest {
    private var clientServerMode: ClientServerMode = ClientServerMode.DISABLED
    private val cachedClientServerMode = CachedClientServerMode { clientServerMode }
    private val linkedDeviceFileSender = mockk<LinkedDeviceFileSender>(relaxed = true)
    private val marFile = File("fakepath")
    private val testFile = File("fakemarpath")
    private val marFileWriter = mockk<MarFileWriter> {
        coEvery { createForReboot(any(), any()) } answers { marFile }
        coEvery { createForFile(any(), any(), any()) } answers { marFile }
    }
    private val ingressServiceCall = mockk<Call<Unit>>(relaxed = true)
    private val ingressService = mockk<IngressService> {
        every { uploadRebootEvents(any()) } returns ingressServiceCall
    }
    private val context = mockk<Context>()
    private val enqueuePreparedUploadTask = mockk<EnqueuePreparedUploadTask>(relaxed = true)
    private val marHoldingArea: MarFileHoldingArea = mockk(relaxed = true)
    private val enqueueUpload = EnqueueUpload(
        context = context,
        linkedDeviceFileSender = linkedDeviceFileSender,
        marFileWriter = marFileWriter,
        ingressService = ingressService,
        cachedClientServerMode = cachedClientServerMode,
        enqueuePreparedUploadTask = enqueuePreparedUploadTask,
        useMarUpload = { useMarUpload },
        marHoldingArea = marHoldingArea,
    )
    private val continuation = mockk<FileUploadContinuation>(relaxed = true)
    private val combinedTimeProvider = FakeCombinedTimeProvider
    private val metadata = BugReportFileUploadPayload(
        hardwareVersion = "",
        deviceSerial = "",
        softwareVersion = "",
        softwareType = "",
    )
    private var useMarUpload = false

    @Test
    fun enqueueMarFileClientServerUpload_marTrue() {
        clientServerMode = ClientServerMode.CLIENT
        useMarUpload = true
        enqueueUpload()
        verifySent(clientServer = true, mar = false, preparedUpload = false)
        verify { continuation.success(context) }
    }

    @Test
    fun enqueueMarFileClientServerUpload_marFalse() {
        clientServerMode = ClientServerMode.CLIENT
        useMarUpload = false
        enqueueUpload()
        verifySent(clientServer = true, mar = false, preparedUpload = false)
        verify { continuation.success(context) }
    }

    @Test
    fun enqueueMarFileUpload() {
        clientServerMode = ClientServerMode.DISABLED
        useMarUpload = true
        enqueueUpload()
        verifySent(clientServer = false, mar = true, preparedUpload = false)
        verify { continuation.success(context) }
    }

    @Test
    fun enqueuePreparedUpload() {
        clientServerMode = ClientServerMode.DISABLED
        useMarUpload = false
        enqueueUpload()
        verifySent(clientServer = false, mar = false, preparedUpload = true)
    }

    private fun enqueueUpload() {
        enqueueUpload.enqueue(
            file = testFile,
            metadata = metadata,
            debugTag = "hello",
            collectionTime = combinedTimeProvider.now(),
            continuation = continuation,
        )
    }

    private fun Boolean.times() = if (this) 1 else 0

    private fun verifySent(clientServer: Boolean, mar: Boolean, preparedUpload: Boolean) {
        coVerify(exactly = clientServer.times(), timeout = TIMEOUT_MS) {
            linkedDeviceFileSender.sendFileToLinkedDevice(marFile, CLIENT_SERVER_FILE_UPLOAD_DROPBOX_TAG)
        }
        coVerify(exactly = preparedUpload.times(), timeout = TIMEOUT_MS) {
            enqueuePreparedUploadTask.upload(testFile, any(), any(), any(), any())
        }
        coVerify(exactly = mar.times(), timeout = TIMEOUT_MS) {
            marHoldingArea.addMarFile(marFile)
        }
    }

    companion object {
        private const val TIMEOUT_MS: Long = 1000
    }
}
