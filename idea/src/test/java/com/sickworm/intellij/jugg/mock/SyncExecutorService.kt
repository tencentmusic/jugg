package com.sickworm.intellij.jugg.mock

import java.util.concurrent.*

class SyncExecutorService: ExecutorService {
    override fun execute(command: Runnable) {
        command.run()
    }

    override fun shutdown() {
        TODO("Not yet implemented")
    }

    override fun shutdownNow(): MutableList<Runnable> {
        TODO("Not yet implemented")
    }

    override fun isShutdown(): Boolean {
        return false
    }

    override fun isTerminated(): Boolean {
        return false
    }

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        return true
    }

    override fun <T : Any?> submit(task: Callable<T>): Future<T> {
        val result = task.call()
        return CompletedFuture(result, null)
    }

    override fun <T : Any?> submit(task: Runnable, result: T): Future<T> {
        task.run()
        return CompletedFuture(result, null)
    }

    override fun submit(task: Runnable): Future<*> {
        task.run()
        return CompletedFuture(null, null)
    }

    override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> invokeAll(
        tasks: MutableCollection<out Callable<T>>,
        timeout: Long,
        unit: TimeUnit
    ): MutableList<Future<T>> {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>): T {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit): T {
        TODO("Not yet implemented")
    }

    open class CompletedFuture<T>(private val v: T, private val re: Throwable?) : Future<T> {
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            return false
        }

        override fun isCancelled(): Boolean {
            return false
        }

        override fun isDone(): Boolean {
            return true
        }

        @Throws(ExecutionException::class)
        override fun get(): T {
            return if (re != null) {
                throw ExecutionException(re)
            } else {
                v
            }
        }

        @Throws(ExecutionException::class)
        override fun get(timeout: Long, unit: TimeUnit): T {
            return this.get()
        }
    }
}