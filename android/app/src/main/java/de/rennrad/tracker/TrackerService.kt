package de.rennrad.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future

private const val NOTIFICATION = 10
private const val CHANNEL = "tracking"
private const val SYNC_JOB = 11

class TrackerService : Service(), LocationListener {
    private val handler = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private val location by lazy { getSystemService(LocationManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var upload: Future<*>? = null
    private var running = false
    private var wallStart = 0L
    private var elapsedStart = 0L
    private var lastSample = 0L

    private val send = object : Runnable {
        override fun run() {
            if (!running) return
            if (upload?.isDone != false) {
                upload = network.submit {
                    val pending = try {
                        Sync.flush(applicationContext)
                        false
                    } catch (_: Exception) {
                        true
                    }
                    handler.post {
                        if (running) {
                            uploadPending = pending
                            notifications.notify(NOTIFICATION, notification())
                            changed()
                        }
                    }
                }
            }
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopRide()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        try {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION, notification)

            if (!location.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Toast.makeText(this, R.string.gps_enable, Toast.LENGTH_LONG).show()
                stopRide()
                return START_NOT_STICKY
            }
            wallStart = System.currentTimeMillis()
            elapsedStart = SystemClock.elapsedRealtime()
            RideFiles.start(this)
            running = true
            active = true
            location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000, 0f, this, Looper.getMainLooper())
            // Schedule a fallback in case Android ends the process before the rider taps Stop.
            SyncJob.enqueue(this)
            handler.post(send)
            changed()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.ride_start_error, Toast.LENGTH_LONG).show()
            stopRide()
        }
        return START_NOT_STICKY
    }

    override fun onLocationChanged(fix: Location) {
        if (!running) return
        val elapsed = fix.elapsedRealtimeNanos / 1_000_000
        if (elapsed < elapsedStart || elapsed - lastSample < 1_750) return
        val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val point = JSONObject().put("time", wallStart + elapsed - elapsedStart)
            .put("lat", fix.latitude).put("lon", fix.longitude)
        if (fix.hasSpeed()) point.put("speed", fix.speed.toDouble())
        if (fix.hasBearing()) point.put("bearing", fix.bearing.toDouble())
        if (fix.hasAltitude()) point.put("altitude", fix.altitude)
        if (fix.hasAccuracy()) point.put("accuracy", fix.accuracy.toDouble())
        if (battery in 0..100) point.put("battery", battery)
        try {
            RideFiles.point(point)
            lastSample = elapsed
            if (!gpsReady) {
                gpsReady = true
                handler.removeCallbacks(send)
                handler.post(send)
                changed()
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.point_error, Toast.LENGTH_LONG).show()
            stopRide()
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackerService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ride)
            .setContentTitle(getString(R.string.ride_active))
            .setContentText(getString(when {
                uploadPending -> R.string.upload_pending
                gpsReady -> R.string.gps_recording
                else -> R.string.gps_wait
            }))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.ride_stop), stop).build())
            .build()
    }

    private fun stopRide() {
        finishRecording()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishRecording() {
        if (!running) return
        running = false
        handler.removeCallbacks(send)
        location.removeUpdates(this)
        try {
            RideFiles.finish()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.ride_end_retry, Toast.LENGTH_LONG).show()
        }
        SyncJob.enqueue(this, replace = true)
        active = false
        gpsReady = false
        uploadPending = false
        changed()
    }

    private fun changed() = sendBroadcast(Intent(UPDATE).setPackage(packageName))

    override fun onDestroy() {
        finishRecording()
        network.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val STOP = "de.rennrad.tracker.STOP"
        const val UPDATE = "de.rennrad.tracker.UPDATE"
        var active = false
            private set
        var gpsReady = false
            private set
        var uploadPending = false
            private set
    }
}

class SyncJob : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    private val main = Handler(Looper.getMainLooper())
    private var current: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        current = params
        task = executor.submit {
            val retry = try {
                Sync.flush(applicationContext)
                RideFiles.hasPending(applicationContext)
            } catch (_: Exception) { true }
            main.post {
                if (current === params) {
                    current = null
                    jobFinished(params, retry)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        current = null
        task?.cancel(true)
        return true
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        fun enqueue(context: Context, replace: Boolean = false) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!replace && scheduler.getPendingJob(SYNC_JOB) != null) return
            val job = JobInfo.Builder(SYNC_JOB, ComponentName(context, SyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }
    }
}
