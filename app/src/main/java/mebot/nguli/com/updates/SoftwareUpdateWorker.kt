package mebot.nguli.com.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import mebot.nguli.com.MainActivity
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.R
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class SoftwareUpdateWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result {
        val app=applicationContext as MeBotApplication;val manager=app.softwareUpdates
        if(!manager.state.value.automaticChecks)return Result.success()
        return try{
            manager.check()
            val state=manager.state.value
            val release=state.release ?: return if(state.error!=null && runAttemptCount<3)Result.retry() else Result.success()
            if(state.downloadOnWifi && !app.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered)manager.download()
            val prefs=app.getSharedPreferences("mebot.software-updates.v1",Context.MODE_PRIVATE)
            val allowed=Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(app,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
            if(allowed && prefs.getInt("notified",0)<release.versionCode){
                val nm=app.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel("mebot_software_updates",app.getString(R.string.software_updates),NotificationManager.IMPORTANCE_DEFAULT))
                val intent=Intent(app,MainActivity::class.java).setAction("mebot.nguli.com.OPEN_SOFTWARE_UPDATES").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val pending=PendingIntent.getActivity(app,301,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                nm.notify(301,NotificationCompat.Builder(app,"mebot_software_updates").setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(app.getString(R.string.update_available)).setContentText(app.getString(R.string.update_notification_body))
                    .setContentIntent(pending).setAutoCancel(true).build())
                prefs.edit().putInt("notified",release.versionCode).apply()
            }
            Result.success()
        }catch(e:CancellationException){throw e}catch(e:Exception){if(runAttemptCount<3)Result.retry()else Result.failure()}
    }
    companion object{
        fun schedule(context:Context,enabled:Boolean){
            val work=WorkManager.getInstance(context)
            if(!enabled){work.cancelUniqueWork("mebot-software-updates");return}
            work.enqueueUniquePeriodicWork("mebot-software-updates",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SoftwareUpdateWorker>(24,TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build())
        }
    }
}
