package com.margelo.nitro.locationsession.engine

import android.content.Context
import com.margelo.nitro.locationsession.core.DebugOptions
import com.margelo.nitro.locationsession.core.validateDebugOptions

object DebugLogger {
    fun configure(context: Context, options: DebugOptions) {
        validateDebugOptions(options)?.let { throw LocationSessionException("INVALID_CONFIG", it) }
    }

    fun log(level: String, tag: String, msg: String, data: Map<String, Any?>? = null, sessionId: String? = null) {}

    fun read(context: Context, limit: Int): String = "[]"

    fun clear(context: Context) {}

    fun flush(context: Context, completion: (Int) -> Unit) {
        completion(0)
    }

    fun onUploadCompleted() {}

    fun onStopping() {}
}
