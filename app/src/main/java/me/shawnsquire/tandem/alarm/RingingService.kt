package me.shawnsquire.tandem.alarm

import android.app.Service
import android.content.Intent
import android.os.IBinder

class RingingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
