package com.margelo.nitro.locationsession.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper

class SessionInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let {
            DebugLogger.processStarted(it)
            SessionEngine.attach(it)
            val app = it.applicationContext
            Handler(Looper.getMainLooper()).post {
                EngineThread.post { SessionEngine.drainAtProcessStart(app) }
            }
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
