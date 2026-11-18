package com.margelo.nitro.locationsession.core

sealed class UploadOutcome {
    object Success : UploadOutcome()
    data class Stop(val reason: StopReason) : UploadOutcome()
    object Drop : UploadOutcome()
    object Retry : UploadOutcome()
}

fun classify(status: Int?): UploadOutcome {
    if (status == null) return UploadOutcome.Retry
    return when (status) {
        in 200..299 -> UploadOutcome.Success
        401, 403 -> UploadOutcome.Stop(StopReason.UNAUTHORIZED)
        410 -> UploadOutcome.Stop(StopReason.REJECTED)
        in 400..499 -> UploadOutcome.Drop
        else -> UploadOutcome.Retry
    }
}

private val backoffSteps = longArrayOf(5000, 10000, 20000, 40000)

fun backoffMs(attempt: Int): Long {
    if (attempt < 0) return backoffSteps[0]
    return if (attempt < backoffSteps.size) backoffSteps[attempt] else 60000
}

fun shouldUploadNow(lastSuccessMs: Long?, nowMs: Long, minIntervalMs: Long, bypass: Boolean): Boolean {
    if (bypass) return true
    val last = lastSuccessMs ?: return true
    return nowMs - last >= minIntervalMs
}
