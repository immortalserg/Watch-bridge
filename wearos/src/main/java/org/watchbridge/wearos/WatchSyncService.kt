package org.watchbridge.wearos

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/** User-enabled foreground collection. No automatic SpO2 measurement is attempted. */
class WatchSyncService : Service(), SensorEventListener {
 companion object {
  const val ACTION_START = "org.watchbridge.wearos.START_SYNC"
  const val ACTION_STOP = "org.watchbridge.wearos.STOP_SYNC"
  private const val CHANNEL = "watch_bridge_sync"
  private const val NOTIFICATION_ID = 2301
 }
 private val prefs by lazy { getSharedPreferences("watch_bridge", MODE_PRIVATE) }
 private lateinit var sensors: SensorManager
 private var mqtt: WatchMqtt? = null
 private var ble: WatchBleServer? = null
 override fun onBind(intent: Intent?): IBinder? = null
 override fun onCreate() {
  super.onCreate()
  sensors = getSystemService(SENSOR_SERVICE) as SensorManager
  (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
   .createNotificationChannel(NotificationChannel(CHANNEL, "Watch Bridge — синхронизация", NotificationManager.IMPORTANCE_LOW))
 }
 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  if (intent?.action == ACTION_STOP || !prefs.getBoolean("background_sync_enabled", false)) {
   stopSelf()
   return START_NOT_STICKY
  }
  val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
   PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val notification = Notification.Builder(this, CHANNEL)
   .setSmallIcon(android.R.drawable.stat_notify_sync)
   .setContentTitle("Watch Bridge")
   .setContentText("Сбор измерений и синхронизация MQTT")
   .setContentIntent(open).setOngoing(true).build()
  try {
   if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
   else startForeground(NOTIFICATION_ID, notification)
  } catch (e: Exception) {
   Log.e("WatchBridgeSync", "Foreground service start failed", e)
   stopSelf()
   return START_NOT_STICKY
  }
  if (mqtt == null) mqtt = WatchMqtt(this) { message -> Log.d("WatchBridgeSync", message) }
  mqtt?.retryPending()
  if (prefs.getBoolean("ble_enabled", false)) {
   if (ble == null) ble = WatchBleServer(this) { message -> Log.d("WatchBridgeSyncBLE", message) }
   ble?.start()
  } else { ble?.stop(); ble = null }
  sensors.unregisterListener(this)
  if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
   sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
  if (prefs.getBoolean("auto_heart_collection", false) &&
      checkSelfPermission("android.permission.health.READ_HEART_RATE") == PackageManager.PERMISSION_GRANTED)
   sensors.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
  return START_STICKY
 }
 override fun onSensorChanged(event: SensorEvent) {
  if (event.values.isEmpty()) return
  when (event.sensor.type) {
   Sensor.TYPE_HEART_RATE -> {
    if (!prefs.getBoolean("auto_heart_collection", false)) return
    val value = event.values[0]
    if (!value.isFinite() || value !in 25f..240f) return
    val now = System.currentTimeMillis()
    val old = try { JSONArray(prefs.getString("vital_history_heart", "[]")) } catch (_: Exception) { JSONArray() }
    if (old.length() > 0 && old.optJSONObject(0)?.optInt("value") == value.toInt() &&
     now - (old.optJSONObject(0)?.optLong("time") ?: 0L) < 60_000L) return
    val updated = JSONArray().put(JSONObject().put("time", now).put("value", value.toInt()))
    for (i in 0 until minOf(old.length(), 49)) updated.put(old.opt(i))
    prefs.edit().putString("vital_history_heart", updated.toString())
     .putString("last_heart", "${value.toInt()} уд/мин").apply()
    val data = JSONObject().put("kind", "heart").put("id", "heart:$now")
     .put("measured_at", Instant.ofEpochMilli(now).toString()).put("bpm", value.toInt())
    mqtt?.publish("heart", data.toString())
    ble?.publishJournal(data.toString())
   }
   Sensor.TYPE_STEP_COUNTER -> {
    val value = event.values[0]
    if (!value.isFinite() || value < 0f) return
    val day = LocalDate.now().toString()
    val history = try { JSONObject(prefs.getString("step_daily_history", "{}")) } catch (_: Exception) { JSONObject() }
    val entry = history.optJSONObject(day) ?: JSONObject()
    val current = value.toLong()
    val previous = entry.optLong("lastCounter", -1L)
    val increment = if (previous < 0L) 0L else if (current >= previous) current - previous else current
    val updated = (entry.optLong("steps") + increment).coerceAtMost(Int.MAX_VALUE.toLong())
    entry.put("steps", updated).put("lastCounter", current).put("lastUpdate", System.currentTimeMillis())
    history.put(day, entry)
    history.keys().asSequence().toList().sortedDescending().drop(90).forEach { history.remove(it) }
    prefs.edit().putString("step_daily_history", history.toString()).apply()
    if (increment > 0) {
     val data = JSONObject().put("kind", "steps").put("id", "steps:$day")
      .put("measured_at", Instant.now().toString()).put("date", day)
      .put("steps", updated).put("diatance", 0).put("kcal", 0)
     mqtt?.publish("steps", data.toString())
     ble?.publishJournal(data.toString())
    }
   }
  }
 }
 override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
 override fun onDestroy() {
  sensors.unregisterListener(this)
  mqtt?.close()
  mqtt = null
  ble?.stop()
  ble = null
  super.onDestroy()
 }
}
