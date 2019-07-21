package be.tramckrijte.workmanager

import android.os.Build
import androidx.work.*
import androidx.work.PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS
import be.tramckrijte.workmanager.WorkManagerCall.CancelTask.ByTag.KEYS.UNREGISTER_TASK_TAG_KEY
import be.tramckrijte.workmanager.WorkManagerCall.CancelTask.ByUniqueName.KEYS.UNREGISTER_TASK_UNIQUE_NAME_KEY
import be.tramckrijte.workmanager.WorkManagerCall.RegisterTask.KEYS.REGISTER_TASK_BACK_OFF_POLICY_DELAY_MILLIS_KEY
import be.tramckrijte.workmanager.WorkManagerCall.RegisterTask.KEYS.REGISTER_TASK_BACK_OFF_POLICY_TYPE_KEY
import be.tramckrijte.workmanager.WorkManagerCall.RegisterTask.KEYS.REGISTER_TASK_CONSTRAINTS_BATTERY_NOT_LOW_KEY
import be.tramckrijte.workmanager.WorkManagerCall.RegisterTask.KEYS.REGISTER_TASK_CONSTRAINTS_CHARGING_KEY
import be.tramckrijte.workmanager.WorkManagerCall.RegisterTask.KEYS.REGISTER_TASK_CONSTRAINTS_DEVICE_IDLE_KEY
import be.tramckrijte.workmanager.WorkManagerCall.RegisterTask.KEYS.REGISTER_TASK_CONSTRAINTS_NETWORK_TYPE_KEY
import be.tramckrijte.workmanager.WorkManagerCall.RegisterTask.KEYS.REGISTER_TASK_CONSTRAINTS_STORAGE_NOT_LOW_KEY
import be.tramckrijte.workmanager.WorkManagerCall.RegisterTask.KEYS.REGISTER_TASK_ECHO_VALUE_KEY
import be.tramckrijte.workmanager.WorkManagerCall.RegisterTask.KEYS.REGISTER_TASK_EXISTING_WORK_POLICY_KEY
import be.tramckrijte.workmanager.WorkManagerCall.RegisterTask.KEYS.REGISTER_TASK_INITIAL_DELAY_SECONDS_KEY
import be.tramckrijte.workmanager.WorkManagerCall.RegisterTask.KEYS.REGISTER_TASK_IS_IN_DEBUG_MODE_KEY
import be.tramckrijte.workmanager.WorkManagerCall.RegisterTask.KEYS.REGISTER_TASK_TAG_KEY
import be.tramckrijte.workmanager.WorkManagerCall.RegisterTask.KEYS.REGISTER_TASK_UNIQUE_NAME_KEY
import be.tramckrijte.workmanager.WorkManagerCall.RegisterTask.PeriodicTask.KEYS.PERIODIC_TASK_FREQUENCY_SECONDS_KEY
import io.flutter.plugin.common.MethodCall
import kotlin.math.max

data class BackoffPolicyTaskConfig(val backoffPolicy: BackoffPolicy,
                                   private val requestedBackoffDelay: Long,
                                   private val minBackoffInMillis: Long,
                                   val backoffDelay: Long = max(minBackoffInMillis, requestedBackoffDelay))

sealed class WorkManagerCall {
    data class Initialize(val callbackDispatcherHandleKey: Long) : WorkManagerCall()

    sealed class RegisterTask : WorkManagerCall() {
        abstract val isInDebugMode: Boolean
        abstract val uniqueName: String
        abstract val valueToReturn: String
        abstract val tag: String?
        abstract val initialDelaySeconds: Long
        abstract val constraintsConfig: Constraints?

        companion object KEYS {
            const val REGISTER_TASK_IS_IN_DEBUG_MODE_KEY = "isInDebugMode"
            const val REGISTER_TASK_UNIQUE_NAME_KEY = "uniqueName"
            const val REGISTER_TASK_ECHO_VALUE_KEY = "echoValue"
            const val REGISTER_TASK_TAG_KEY = "tag"
            const val REGISTER_TASK_EXISTING_WORK_POLICY_KEY = "existingWorkPolicy"

            const val REGISTER_TASK_CONSTRAINTS_NETWORK_TYPE_KEY = "networkType"
            const val REGISTER_TASK_CONSTRAINTS_BATTERY_NOT_LOW_KEY = "requiresBatteryNotLow"
            const val REGISTER_TASK_CONSTRAINTS_CHARGING_KEY = "requiresCharging"
            const val REGISTER_TASK_CONSTRAINTS_DEVICE_IDLE_KEY = "requiresDeviceIdle"
            const val REGISTER_TASK_CONSTRAINTS_STORAGE_NOT_LOW_KEY = "requiresStorageNotLow"

            const val REGISTER_TASK_INITIAL_DELAY_SECONDS_KEY = "initialDelaySeconds"

            const val REGISTER_TASK_BACK_OFF_POLICY_TYPE_KEY = "backoffPolicyType"
            const val REGISTER_TASK_BACK_OFF_POLICY_DELAY_MILLIS_KEY = "backoffDelayInMilliseconds"
        }

        data class OneOffTask(override val isInDebugMode: Boolean,
                              override val uniqueName: String,
                              override val valueToReturn: String,
                              override val tag: String? = null,
                              val existingWorkPolicy: ExistingWorkPolicy,
                              override val initialDelaySeconds: Long,
                              override val constraintsConfig: Constraints,
                              val backoffPolicyConfig: BackoffPolicyTaskConfig) : RegisterTask()

        data class PeriodicTask(override val isInDebugMode: Boolean,
                                override val uniqueName: String,
                                override val valueToReturn: String,
                                override val tag: String? = null,
                                val existingWorkPolicy: ExistingPeriodicWorkPolicy,
                                val frequencyInSeconds: Long,
                                override val initialDelaySeconds: Long,
                                override val constraintsConfig: Constraints,
                                val backoffPolicyConfig: BackoffPolicyTaskConfig) : RegisterTask() {
            companion object KEYS {
                const val PERIODIC_TASK_FREQUENCY_SECONDS_KEY = "frequency"
            }
        }
    }

    sealed class CancelTask : WorkManagerCall() {
        data class ByUniqueName(val uniqueName: String) : CancelTask() {
            companion object KEYS {
                const val UNREGISTER_TASK_UNIQUE_NAME_KEY = "uniqueName"
            }
        }

        data class ByTag(val tag: String) : CancelTask() {
            companion object KEYS {
                const val UNREGISTER_TASK_TAG_KEY = "tag"
            }
        }

        object All : CancelTask()
    }

    object Unknown : WorkManagerCall()
}

private enum class TaskType(val minimumBackOffDelay: Long) {
    ONE_OFF(OneTimeWorkRequest.MIN_BACKOFF_MILLIS),
    PERIODIC(PeriodicWorkRequest.MIN_BACKOFF_MILLIS)
}

object Extractor {
    private enum class PossibleWorkManagerCall(val rawMethodName: String?) {
        INITIALIZE("initialize"),

        REGISTER_ONE_OFF_TASK("registerOneOffTask"),
        REGISTER_PERIODIC_TASK("registerPeriodicTask"),

        CANCEL_TASK_BY_UNIQUE_NAME("cancelTaskByUniqueName"),
        CANCEL_TASK_BY_TAG("cancelTaskByTag"),
        CANCEL_ALL("cancelAllTasks"),

        UNKNOWN(null);

        companion object {
            fun fromRawMethodName(methodName: String): PossibleWorkManagerCall =
                    values()
                            .filter { !it.rawMethodName.isNullOrEmpty() }
                            .firstOrNull { it.rawMethodName == methodName }
                            ?: UNKNOWN
        }
    }

    fun extractWorkManagerCallFromRawMethodName(call: MethodCall): WorkManagerCall =
            when (PossibleWorkManagerCall.fromRawMethodName(call.method)) {
                Extractor.PossibleWorkManagerCall.INITIALIZE -> WorkManagerCall.Initialize(call.arguments() as Long)
                Extractor.PossibleWorkManagerCall.REGISTER_ONE_OFF_TASK -> {
                    WorkManagerCall.RegisterTask.OneOffTask(
                            isInDebugMode = call.argument<Boolean>(REGISTER_TASK_IS_IN_DEBUG_MODE_KEY)!!,
                            uniqueName = call.argument<String>(REGISTER_TASK_UNIQUE_NAME_KEY)!!,
                            valueToReturn = call.argument<String>(REGISTER_TASK_ECHO_VALUE_KEY)!!,
                            tag = call.argument<String>(REGISTER_TASK_TAG_KEY),
                            existingWorkPolicy = extractExistingWorkPolicyFromCall(call),
                            initialDelaySeconds = extractInitialDelayFromCall(call),
                            constraintsConfig = extractConstraintConfigFromCall(call),
                            backoffPolicyConfig = extractBackoffPolicyConfigFromCall(call, TaskType.ONE_OFF)
                    )
                }
                Extractor.PossibleWorkManagerCall.REGISTER_PERIODIC_TASK -> {
                    WorkManagerCall.RegisterTask.PeriodicTask(
                            isInDebugMode = call.argument<Boolean>(REGISTER_TASK_IS_IN_DEBUG_MODE_KEY)!!,
                            uniqueName = call.argument<String>(REGISTER_TASK_UNIQUE_NAME_KEY)!!,
                            valueToReturn = call.argument<String>(REGISTER_TASK_ECHO_VALUE_KEY)!!,
                            frequencyInSeconds = extractFrequencySecondsFromCall(call),
                            tag = call.argument<String>(REGISTER_TASK_TAG_KEY),
                            existingWorkPolicy = extractExistingPeriodicWorkPolicyFromCall(call),
                            initialDelaySeconds = extractInitialDelayFromCall(call),
                            constraintsConfig = extractConstraintConfigFromCall(call),
                            backoffPolicyConfig = extractBackoffPolicyConfigFromCall(call, TaskType.PERIODIC)
                    )
                }

                Extractor.PossibleWorkManagerCall.CANCEL_TASK_BY_UNIQUE_NAME -> WorkManagerCall.CancelTask.ByUniqueName(call.argument(UNREGISTER_TASK_UNIQUE_NAME_KEY)!!)
                Extractor.PossibleWorkManagerCall.CANCEL_TASK_BY_TAG -> WorkManagerCall.CancelTask.ByTag(call.argument(UNREGISTER_TASK_TAG_KEY)!!)
                Extractor.PossibleWorkManagerCall.CANCEL_ALL -> WorkManagerCall.CancelTask.All

                Extractor.PossibleWorkManagerCall.UNKNOWN -> WorkManagerCall.Unknown
            }

    private fun extractExistingWorkPolicyFromCall(call: MethodCall): ExistingWorkPolicy =
            try {
                ExistingWorkPolicy.valueOf(call.argument<String>(REGISTER_TASK_EXISTING_WORK_POLICY_KEY)!!.toUpperCase())
            } catch (ignored: Exception) {
                ExistingWorkPolicy.KEEP
            }

    private fun extractExistingPeriodicWorkPolicyFromCall(call: MethodCall): ExistingPeriodicWorkPolicy =
            try {
                ExistingPeriodicWorkPolicy.valueOf(call.argument<String>(REGISTER_TASK_EXISTING_WORK_POLICY_KEY)!!.toUpperCase())
            } catch (ignored: Exception) {
                ExistingPeriodicWorkPolicy.KEEP
            }

    private fun extractFrequencySecondsFromCall(call: MethodCall): Long =
            call.argument<Int>(PERIODIC_TASK_FREQUENCY_SECONDS_KEY)?.toLong()
                    ?: MIN_PERIODIC_INTERVAL_MILLIS

    private fun extractInitialDelayFromCall(call: MethodCall): Long =
            call.argument<Int>(REGISTER_TASK_INITIAL_DELAY_SECONDS_KEY)?.toLong() ?: 0L

    private fun extractBackoffPolicyConfigFromCall(call: MethodCall, taskType: TaskType): BackoffPolicyTaskConfig {
        val backoffPolicy = try {
            BackoffPolicy.valueOf(call.argument<String>(REGISTER_TASK_BACK_OFF_POLICY_TYPE_KEY)!!.toUpperCase())
        } catch (ignored: Exception) {
            BackoffPolicy.EXPONENTIAL
        }

        val requestedBackoffDelay = call.argument<Int>(REGISTER_TASK_BACK_OFF_POLICY_DELAY_MILLIS_KEY)?.toLong()
                ?: 0L
        val minimumBackOffDelay = taskType.minimumBackOffDelay

        return BackoffPolicyTaskConfig(
                backoffPolicy,
                requestedBackoffDelay,
                minimumBackOffDelay
        )
    }

    private fun extractConstraintConfigFromCall(call: MethodCall): Constraints {
        fun extractNetworkTypeFromCall(call: MethodCall) =
                try {
                    NetworkType.valueOf(call.argument<String>(REGISTER_TASK_CONSTRAINTS_NETWORK_TYPE_KEY)!!.toUpperCase())
                } catch (ignored: Exception) {
                    NetworkType.NOT_REQUIRED
                }

        val requestedNetworkType = extractNetworkTypeFromCall(call)
        val requiresBatteryNotLow = call.argument<Boolean>(REGISTER_TASK_CONSTRAINTS_BATTERY_NOT_LOW_KEY)
                ?: false
        val requiresCharging = call.argument<Boolean>(REGISTER_TASK_CONSTRAINTS_CHARGING_KEY)
                ?: false
        val requiresDeviceIdle = call.argument<Boolean>(REGISTER_TASK_CONSTRAINTS_DEVICE_IDLE_KEY)
                ?: false
        val requiresStorageNotLow = call.argument<Boolean>(REGISTER_TASK_CONSTRAINTS_STORAGE_NOT_LOW_KEY)
                ?: false
        return Constraints.Builder()
                .setRequiredNetworkType(requestedNetworkType)
                .setRequiresBatteryNotLow(requiresBatteryNotLow)
                .setRequiresCharging(requiresCharging)
                .setRequiresStorageNotLow(requiresStorageNotLow)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        setRequiresDeviceIdle(requiresDeviceIdle)
                    }
                }
                .build()
    }
}