
package com.openjarvis.automation

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

class AutomationManager(private val context: Context) {

    private val db = AutomationDB.getInstance(context)
    private val dao = db.automationDao()

    private val _automationsFlow =
        MutableStateFlow<List<Automation>>(emptyList())

    val automationsFlow: StateFlow<List<Automation>> = _automationsFlow

    suspend fun loadAutomations() = withContext(Dispatchers.IO) {
        _automationsFlow.value = dao.getAll().map { it.toAutomation() }
    }

    suspend fun createAutomation(automation: Automation): String =
        withContext(Dispatchers.IO) {

            dao.insert(automation.toEntity())

            if (automation.enabled) {
                scheduleAutomation(automation)
            }

            _automationsFlow.value =
                dao.getAll().map { it.toAutomation() }

            automation.id
        }

    suspend fun updateAutomation(automation: Automation) =
        withContext(Dispatchers.IO) {

            dao.update(automation.toEntity())
            cancelAutomation(automation.id)

            if (automation.enabled) {
                scheduleAutomation(automation)
            }

            _automationsFlow.value =
                dao.getAll().map { it.toAutomation() }
        }

    suspend fun deleteAutomation(id: String) =
        withContext(Dispatchers.IO) {

            cancelAutomation(id)
            dao.delete(id)

            _automationsFlow.value =
                dao.getAll().map { it.toAutomation() }
        }

    suspend fun toggleAutomation(id: String, enabled: Boolean) =
        withContext(Dispatchers.IO) {

            val entity = dao.getById(id) ?: return@withContext
            val updated = entity.copy(enabled = enabled)

            dao.update(updated)
            cancelAutomation(id)

            if (enabled) {
                scheduleAutomation(updated.toAutomation())
            }

            _automationsFlow.value =
                dao.getAll().map { it.toAutomation() }
        }

    suspend fun runNow(id: String) =
        withContext(Dispatchers.IO) {

            val entity = dao.getById(id) ?: return@withContext
            executeAutomation(entity.toAutomation())
        }

    private fun scheduleAutomation(automation: Automation) {

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .build()

        val inputData = workDataOf(
            "automation_id" to automation.id,
            "automation_command" to automation.command
        )

        val workManager = WorkManager.getInstance(context)

        when (val schedule = automation.schedule) {

            is AutomationSchedule.Daily -> {

                val request =
                    PeriodicWorkRequestBuilder<AutomationWorker>(
                        24, TimeUnit.HOURS,
                        15, TimeUnit.MINUTES
                    )
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .setInitialDelay(
                            calculateDelay(schedule.hour, schedule.minute),
                            TimeUnit.MILLISECONDS
                        )
                        .addTag(automation.id)
                        .build()

                workManager.enqueueUniquePeriodicWork(
                    automation.id,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            }

            is AutomationSchedule.Weekly -> {

                val request =
                    PeriodicWorkRequestBuilder<AutomationWorker>(
                        7, TimeUnit.DAYS,
                        15, TimeUnit.MINUTES
                    )
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .setInitialDelay(
                            calculateWeeklyDelay(
                                schedule.dayOfWeek,
                                schedule.hour,
                                schedule.minute
                            ),
                            TimeUnit.MILLISECONDS
                        )
                        .addTag(automation.id)
                        .build()

                workManager.enqueueUniquePeriodicWork(
                    automation.id,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            }

            is AutomationSchedule.Interval -> {

                if (schedule.intervalMs < 15 * 60 * 1000L) {
                    return
                }

                val request =
                    PeriodicWorkRequestBuilder<AutomationWorker>(
                        schedule.intervalMs,
                        TimeUnit.MILLISECONDS
                    )
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .addTag(automation.id)
                        .build()

                workManager.enqueueUniquePeriodicWork(
                    automation.id,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            }

            is AutomationSchedule.Once -> {

                val delay =
                    schedule.atMs - System.currentTimeMillis()

                if (delay <= 0L) return

                val request =
                    OneTimeWorkRequestBuilder<AutomationWorker>()
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .setInitialDelay(
                            delay,
                            TimeUnit.MILLISECONDS
                        )
                        .addTag(automation.id)
                        .build()

                workManager.enqueueUniqueWork(
                    automation.id,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }
        }
    }

    private fun cancelAutomation(id: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(id)
        WorkManager.getInstance(context).cancelUniqueWork(id)
    }

    private suspend fun executeAutomation(automation: Automation) {

        val result = try {
            // Placeholder: connect the actual command executor later.
            "success"
        } catch (e: Exception) {
            "error: ${e.message}"
        }

        val updated = automation.copy(
            lastRun = System.currentTimeMillis(),
            lastResult = result,
            runCount = automation.runCount + 1
        )

        dao.update(updated.toEntity())
    }

    private fun calculateDelay(
        targetHour: Int,
        targetMinute: Int
    ): Long {

        val calendar = Calendar.getInstance()
        val now = calendar.timeInMillis

        calendar.set(Calendar.HOUR_OF_DAY, targetHour)
        calendar.set(Calendar.MINUTE, targetMinute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        var delay = calendar.timeInMillis - now

        if (delay <= 0L) {
            delay += TimeUnit.DAYS.toMillis(1)
        }

        return delay
    }

    private fun calculateWeeklyDelay(
        dayOfWeek: Int,
        targetHour: Int,
        targetMinute: Int
    ): Long {

        val calendar = Calendar.getInstance()
        val now = calendar.timeInMillis

        calendar.set(Calendar.DAY_OF_WEEK, dayOfWeek)
        calendar.set(Calendar.HOUR_OF_DAY, targetHour)
        calendar.set(Calendar.MINUTE, targetMinute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        var delay = calendar.timeInMillis - now

        if (delay <= 0L) {
            delay += TimeUnit.DAYS.toMillis(7)
        }

        return delay
    }

    fun parseSchedule(input: String): AutomationSchedule? {

        val lower = input.lowercase()

        val dailyMatch = Regex(
            """every day at (\d{1,2})(?::(\d{2}))?\s*(am|pm)?""",
            RegexOption.IGNORE_CASE
        ).find(lower)

        if (dailyMatch != null) {

            var hour = dailyMatch.groupValues[1].toInt()
            val minute =
                dailyMatch.groupValues[2].toIntOrNull() ?: 0

            val period =
                dailyMatch.groupValues[3].lowercase()

            if (hour !in 0..23 || minute !in 0..59) {
                return null
            }

            if (period.isNotEmpty()) {
                if (hour !in 1..12) return null

                if (period == "pm" && hour != 12) {
                    hour += 12
                }

                if (period == "am" && hour == 12) {
                    hour = 0
                }
            }

            return AutomationSchedule.Daily(hour, minute)
        }

        val intervalMatch = Regex(
            """every (\d+)\s*(minute|hour|day)s?""",
            RegexOption.IGNORE_CASE
        ).find(lower)

        if (intervalMatch != null) {

            val value =
                intervalMatch.groupValues[1].toLongOrNull()
                    ?: return null

            if (value <= 0L) return null

            val unit = intervalMatch.groupValues[2]

            val multiplier = when (unit) {
                "minute" -> 60_000L
                "hour" -> 3_600_000L
                "day" -> 86_400_000L
                else -> return null
            }

            val interval = value * multiplier

            if (interval < 15 * 60 * 1000L) {
                return null
            }

            return AutomationSchedule.Interval(interval)
        }

        return null
    }

    private fun AutomationEntity.toAutomation(): Automation {

        val schedule = when (scheduleType.uppercase()) {

            "DAILY" -> AutomationSchedule.Daily(
                scheduleHour,
                scheduleMinute
            )

            "WEEKLY" -> AutomationSchedule.Weekly(
                scheduleDayOfWeek,
                scheduleHour,
                scheduleMinute
            )

            "INTERVAL" -> AutomationSchedule.Interval(
                scheduleIntervalMs
            )

            "ONCE" -> AutomationSchedule.Once(
                scheduleAtMs
            )

            else -> AutomationSchedule.Daily(
                scheduleHour,
                scheduleMinute
            )
        }

        return Automation(
            id = id,
            name = name,
            command = command,
            schedule = schedule,
            enabled = enabled,
            lastRun = lastRun,
            lastResult = lastResult,
            runCount = runCount
        )
    }

    private fun Automation.toEntity(): AutomationEntity {

        val type: String
        var hour = 0
        var minute = 0
        var dayOfWeek = 0
        var intervalMs = 0L
        var atMs = 0L

        when (val value = schedule) {

            is AutomationSchedule.Daily -> {
                type = "DAILY"
                hour = value.hour
                minute = value.minute
            }

            is AutomationSchedule.Weekly -> {
                type = "WEEKLY"
                dayOfWeek = value.dayOfWeek
                hour = value.hour
                minute = value.minute
            }

            is AutomationSchedule.Interval -> {
                type = "INTERVAL"
                intervalMs = value.intervalMs
            }

            is AutomationSchedule.Once -> {
                type = "ONCE"
                atMs = value.atMs
            }
        }

        return AutomationEntity(
            id = id,
            name = name,
            command = command,
            scheduleType = type,
            scheduleHour = hour,
            scheduleMinute = minute,
            scheduleDayOfWeek = dayOfWeek,
            scheduleIntervalMs = intervalMs,
            scheduleAtMs = atMs,
            enabled = enabled,
            lastRun = lastRun,
            lastResult = lastResult,
            runCount = runCount
        )
    }

    data class Automation(
        val id: String,
        val name: String,
        val command: String,
        val schedule: AutomationSchedule,
        val enabled: Boolean = true,
        val lastRun: Long? = null,
        val lastResult: String? = null,
        val runCount: Int = 0
    )

    sealed class AutomationSchedule {

        data class Daily(
            val hour: Int,
            val minute: Int
        ) : AutomationSchedule()

        data class Weekly(
            val dayOfWeek: Int,
            val hour: Int,
            val minute: Int
        ) : AutomationSchedule()

        data class Interval(
            val intervalMs: Long
        ) : AutomationSchedule()

        data class Once(
            val atMs: Long
        ) : AutomationSchedule()
    }
}
