package com.memfault.bort.metrics

import androidx.work.Data
import com.memfault.bort.DeviceInfoProvider
import com.memfault.bort.HeartbeatFileUploadPayload
import com.memfault.bort.Task
import com.memfault.bort.TaskResult
import com.memfault.bort.TaskRunnerWorker
import com.memfault.bort.shared.Logger
import com.memfault.bort.time.CombinedTime
import com.memfault.bort.time.CombinedTimeProvider
import com.memfault.bort.uploader.EnqueueFileUpload
import java.io.File
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.HashingSource
import okio.blackholeSink
import okio.buffer
import okio.source

class MetricsCollectionTask(
    private val batteryStatsHistoryCollector: BatteryStatsHistoryCollector,
    private val enqueueFileUpload: EnqueueFileUpload,
    private val combinedTimeProvider: CombinedTimeProvider,
    private val lastHeartbeatEndTimeProvider: LastHeartbeatEndTimeProvider,
    private val deviceInfoProvider: DeviceInfoProvider,
) : Task<Unit>() {
    override val maxAttempts: Int = 1
    override fun convertAndValidateInputData(inputData: Data) = Unit

    private fun calculateMd5Hex(file: File): String =
        HashingSource.md5(file.source()).use { hashingSource ->
            hashingSource.buffer().use { source ->
                source.readAll(blackholeSink())
                hashingSource.hash.hex()
            }
        }

    private suspend fun enqueueHeartbeatUpload(
        now: CombinedTime,
        heartbeatInterval: Duration,
        batteryStatsFile: File,
    ) {
        val deviceInfo = deviceInfoProvider.getDeviceInfo()
        enqueueFileUpload(
            batteryStatsFile,
            HeartbeatFileUploadPayload(
                hardwareVersion = deviceInfo.hardwareVersion,
                deviceSerial = deviceInfo.deviceSerial,
                softwareVersion = deviceInfo.softwareVersion,
                collectionTime = now,
                heartbeatIntervalMs = heartbeatInterval.toLongMilliseconds(),
                customMetrics = emptyMap(),
                builtinMetrics = emptyMap(),
                attachments = HeartbeatFileUploadPayload.Attachments(
                    batteryStats = HeartbeatFileUploadPayload.Attachments.BatteryStats(
                        file = HeartbeatFileUploadPayload.Attachments.FileEntry(
                            md5 = withContext(Dispatchers.IO) {
                                calculateMd5Hex(batteryStatsFile)
                            },
                            name = "batterystats.txt",
                        )
                    )
                )
            ),
            DEBUG_TAG
        )
    }

    override suspend fun doWork(worker: TaskRunnerWorker, input: Unit): TaskResult {
        val now = combinedTimeProvider.now()
        val heartbeatInterval =
            (now.elapsedRealtime.duration - lastHeartbeatEndTimeProvider.lastEnd.elapsedRealtime.duration)

        // The batteryStatsHistoryCollector will use the NEXT time from the previous run and use that as starting
        // point for the data to collect. In practice, this roughly matches the start of the current heartbeat period.
        // But, in case that got screwy for some reason, impose a somewhat arbitrary limit on how much batterystats data
        // we collect, because the history can grow *very* large. In the backend, any extra data before it, will get
        // clipped when aggregating, so it doesn't matter if there's more.
        val batteryStatsLimit = heartbeatInterval * 2

        // MFLT-2593 Bort: refactor FileUploader / HeartbeatFileUploadPayload to upload w/o batterystats
        val batteryStatsFile = try {
            batteryStatsHistoryCollector.collect(
                limit = batteryStatsLimit
            )
        } catch (e: Exception) {
            Logger.e("Failed to collect batterystats", e)
            return TaskResult.FAILURE
        }

        enqueueHeartbeatUpload(now, heartbeatInterval, batteryStatsFile)

        lastHeartbeatEndTimeProvider.lastEnd = now
        return TaskResult.SUCCESS
    }
}

private const val DEBUG_TAG = "UPLOAD_HEARTBEAT"
