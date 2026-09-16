package executor

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

data class Task(
    val id: UUID = UUID.randomUUID(),
    val action: suspend CoroutineScope.() -> Unit
)

object TaskManager {

    private const val MAX_CONCURRENT = 16

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)
    private val runningTasks = ConcurrentHashMap<UUID, Pair<Task, Job>>()
    private val semaphore = Semaphore(MAX_CONCURRENT)

    fun submit(task: Task): Job {
        val job = scope.launch {
            semaphore.withPermit {
                try {
                    task.action(this)
                    println("Completed task ${task.id}")
                } catch (e: CancellationException) {
                    println("Cancelled task ${task.id}")
                    throw e
                } catch (e: Exception) {
                    println("Error with task ${task.id}")
                } finally {
                    runningTasks.remove(task.id)
                }
            }
        }
        runningTasks[task.id] = task to job
        return job
    }

    fun cancel(taskId: UUID) {
        runningTasks[taskId]?.second?.cancel()
    }
}