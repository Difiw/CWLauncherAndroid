package ru.cw.launcher.work

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import ru.cw.launcher.engine.PhoneVm

/** Держит игровой процесс :game живым, пока Minecraft работает в фоне. */
class GameService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = WorkService.build(this, "Minecraft запущен")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTE_ID, notification)
            }
        } catch (_: Exception) {
        }
        return START_NOT_STICKY
    }

    /** The player swiped the game away from recents: record a clean exit so the launcher does not report an error. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        PhoneVm.mark(PhoneVm.Stage.EXITED, 0, "stop")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        private const val NOTE_ID = 47622

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, GameService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
