package org.watchbridge.wearos

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** MQTT transport for local measurements. Does not run as a background service. */
class WatchMqtt(private val context: Context, private val report: (String) -> Unit) {
 private val queue = Executors.newSingleThreadScheduledExecutor()
 private val pendingKey = "mqtt_pending_history"
 private val maxPending = 500
 @Volatile private var lastDeliveredAt = 0L
 init { queue.scheduleWithFixedDelay({ try { drain() } catch (e: Exception) { Log.w("WatchBridgeMQTT", "Retry failed", e) } }, 15, 30, TimeUnit.SECONDS) }
 fun retryPending() { queue.execute { drain() } }
 fun pendingCount(): Int = synchronized(this) { try { JSONArray(prefs.getString(pendingKey, "[]")).length() } catch (_: Exception) { 0 } }
 fun lastDelivered(): Long = lastDeliveredAt
 private var client: MqttClient? = null
 private var currentUri = ""
 private var currentCredentials = ""
 private val prefs get() = context.getSharedPreferences("watch_bridge", Context.MODE_PRIVATE)
 private val deviceId get() = prefs.getString("device_id", "wearos_unknown") ?: "wearos_unknown"

 fun testConnection() { publish("diagnostic", "{\"kind\":\"diagnostic\",\"id\":\"mqtt-test\",\"measured_at\":\"1970-01-01T00:00:00Z\"}") }

 fun publish(kind: String, payload: String) {
  if (kind == "diagnostic") {
   queue.execute { send(kind, payload) }
   return
  }
  synchronized(this) {
   val items = try { JSONArray(prefs.getString(pendingKey, "[]")) } catch (_: Exception) { JSONArray() }
   val id = try { JSONObject(payload).optString("id") } catch (_: Exception) { "" }
   if (id.isNotBlank()) for (i in 0 until items.length()) {
    val old = items.optJSONObject(i) ?: continue
    if (old.optString("id") == id && old.optString("kind") == kind) return
   }
   val record = JSONObject().put("kind", kind).put("payload", payload).put("id", id)
   val updated = JSONArray()
   val start = (items.length() - maxPending + 1).coerceAtLeast(0)
   for (i in start until items.length()) updated.put(items.get(i))
   updated.put(record)
   prefs.edit().putString(pendingKey, updated.toString()).commit()
  }
  queue.execute { drain() }
 }
 private fun drain() {
  if (!prefs.getBoolean("mqtt_enabled", false)) return
  while (true) {
   val next = synchronized(this) {
    try { JSONArray(prefs.getString(pendingKey, "[]")).optJSONObject(0) } catch (_: Exception) { null }
   } ?: return
   if (!send(next.optString("kind"), next.optString("payload"))) return
   synchronized(this) {
    val items = try { JSONArray(prefs.getString(pendingKey, "[]")) } catch (_: Exception) { JSONArray() }
    val updated = JSONArray()
    for (i in 1 until items.length()) updated.put(items.get(i))
    prefs.edit().putString(pendingKey, updated.toString()).commit()
    lastDeliveredAt = System.currentTimeMillis()
   }
  }
 }
 private fun send(kind: String, payload: String): Boolean {
  if (!prefs.getBoolean("mqtt_enabled", false)) return false
  val host = prefs.getString("mqtt_host", "")?.trim().orEmpty()
  val port = prefs.getString("mqtt_port", "8883")?.toIntOrNull() ?: return false
  val tls = prefs.getBoolean("mqtt_tls", true)
  if (host.isEmpty() || port !in 1..65535) { report("MQTT: неверный адрес или порт"); return false }
  var stage = "создание подключения"
  return try {
   val uri = (if (tls) "ssl://" else "tcp://") + host + ":" + port
   val username = prefs.getString("mqtt_user", "").orEmpty().trim()
   val password = prefs.getString("mqtt_password", "").orEmpty()
   val fingerprint = "$username:${password.hashCode()}:$tls"
   if (uri != currentUri || fingerprint != currentCredentials || client?.isConnected != true) {
    try { client?.disconnect() } catch (_: Exception) {}
    try { client?.close() } catch (_: Exception) {}
    val next = MqttClient(uri, "watchbridge_" + deviceId.takeLast(18), MemoryPersistence())
    val opts = MqttConnectOptions().apply {
     isCleanSession = true
     connectionTimeout = 10
     keepAliveInterval = 30
     userName = username.takeIf { it.isNotEmpty() }
     if (password.isNotEmpty()) this.password = password.toCharArray()
    }
    stage = "соединение с брокером"
    next.connect(opts)
    client = next
    currentUri = uri
    currentCredentials = fingerprint
    report("MQTT: подключено")
   }
   stage = "публикация $kind"
   client?.publish("rogbid/$deviceId/history/$kind", payload.toByteArray(Charsets.UTF_8), 1, false)
    ?: throw IllegalStateException("MQTT disconnected")
   report(if (kind == "diagnostic") "MQTT: тест успешен" else "MQTT: отправлено $kind")
   true
  } catch (e: Exception) {
   Log.w("WatchBridgeMQTT", "MQTT [$stage] failed", e)
   report("MQTT [$stage]: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
   false
  }
 }
 fun close() {
  queue.execute {
   try { client?.disconnect() } catch (_: Exception) {}
   try { client?.close() } catch (_: Exception) {}
   client = null
  }
  queue.shutdown()
 }
}
