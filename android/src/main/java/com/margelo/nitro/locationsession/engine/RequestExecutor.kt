package com.margelo.nitro.locationsession.engine

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class RequestExecutor(private val name: String) {
    private var executor = newExecutor()

    fun execute(task: () -> Unit) = executor.execute(task)

    fun abandon() {
        executor.shutdownNow()
        executor = newExecutor()
    }

    private fun newExecutor(): ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, name) }
}
