package com.margelo.nitro.locationsession.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ExpiryAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ElsLog.info("expiry alarm fired")
        val pending = goAsync()
        finishOnce(pending) { finish -> SessionEngine.onExpiryAlarm(context, finish) }
    }
}
