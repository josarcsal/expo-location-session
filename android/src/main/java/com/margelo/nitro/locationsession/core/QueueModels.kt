package com.margelo.nitro.locationsession.core

data class QueueTarget(
    val sessionId: String,
    val url: String,
    val mode: QueueMode,
    val batch: BatchConfig?,
    val extrasJson: String,
    val maxAgeMs: Long,
    val refreshUrl: String?,
    val refreshBodyJson: String?,
    val ended: Boolean
)

data class QueuedRow(
    val id: Long,
    val uuid: String,
    val sessionId: String,
    val fixTs: Long,
    val body: String,
    val attempts: Int
)

data class QueueStatus(val pending: Int, val oldestAt: Long?, val sessions: Int)

data class PurgeResult(val rowsDeleted: Int, val targetsRemoved: List<String>)
