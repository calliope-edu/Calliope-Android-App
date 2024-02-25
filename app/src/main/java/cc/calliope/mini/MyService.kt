package cc.calliope.mini

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class MyService : Service() {

    override fun onBind(intent: Intent): IBinder? {
        // TODO: Return the communication channel to the service.
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("MyService", "Service started")
        // Ваш код для виконання фонових операцій

        // Якщо сервіс вбитий перед виконанням команд, він перезапускається з останньою переданою інтентом командою
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("MyService", "Service destroyed")
        // Ваш код для очищення ресурсів
    }
}
