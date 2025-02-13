package com.memfault.bort.settings

import androidx.work.Data
import com.memfault.bort.DeviceInfoProvider
import com.memfault.bort.Task
import com.memfault.bort.TaskResult
import com.memfault.bort.TaskRunnerWorker
import com.memfault.bort.metrics.BuiltinMetricsStore
import com.memfault.bort.shared.Logger
import com.memfault.bort.tokenbucket.SettingsUpdate
import com.memfault.bort.tokenbucket.TokenBucketStore
import com.memfault.bort.tokenbucket.takeSimple
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

class SettingsUpdateTask @Inject constructor(
    private val deviceInfoProvider: DeviceInfoProvider,
    private val settingsUpdateService: SettingsUpdateService,
    @SettingsUpdate private val tokenBucketStore: TokenBucketStore,
    override val metrics: BuiltinMetricsStore,
    private val settingsUpdateHandler: SettingsUpdateHandler,
) : Task<Unit>() {
    override val getMaxAttempts: () -> Int = { 1 }

    override suspend fun doWork(worker: TaskRunnerWorker, input: Unit): TaskResult {
        if (!tokenBucketStore.takeSimple(tag = "settings")) {
            return TaskResult.FAILURE
        }
        return try {
            val deviceInfo = deviceInfoProvider.getDeviceInfo()
            val new = settingsUpdateService.settings(
                deviceInfo.deviceSerial,
                deviceInfo.softwareVersion,
                deviceInfo.hardwareVersion,
            ).data

            settingsUpdateHandler.handleSettingsUpdate(new)
            TaskResult.SUCCESS
        } catch (e: HttpException) {
            Logger.d("failed to fetch settings from remote endpoint", e)
            TaskResult.SUCCESS
        } catch (e: SerializationException) {
            Logger.d("failed to deserialize fetched settings from remote endpoint", e)
            TaskResult.SUCCESS
        }
    }

    override fun convertAndValidateInputData(inputData: Data) {
    }
}
