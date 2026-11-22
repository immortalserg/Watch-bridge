package org.watchbridge.wearos

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant

/** Experimental local-only GATT bridge; no measurement is generated here. */
class WatchBleServer(private val context: Context, private val report: (String) -> Unit) {
 companion object {
  val SERVICE_UUID: UUID = UUID.fromString("f3e9b8c0-4471-4a42-a14b-6f2f7a91c001")
  val JOURNAL_UUID: UUID = UUID.fromString("f3e9b8c0-4471-4a42-a14b-6f2f7a91c003")
  val HISTORY_UUID: UUID = UUID.fromString("f3e9b8c0-4471-4a42-a14b-6f2f7a91c005")
  val IDENTITY_UUID: UUID = UUID.fromString("f3e9b8c0-4471-4a42-a14b-6f2f7a91c004")
  val DATA_UUID: UUID = UUID.fromString("f3e9b8c0-4471-4a42-a14b-6f2f7a91c002")
  private val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
 }
 private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
 private var server: BluetoothGattServer? = null
 private var dataCharacteristic: BluetoothGattCharacteristic? = null
 private var journalCharacteristic: BluetoothGattCharacteristic? = null
 @Volatile private var latestJournal: ByteArray = byteArrayOf()
 private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
 private val subscribers = mutableSetOf<String>()
 private val historyReplies = mutableMapOf<String, ByteArray>()
 private var latest: ByteArray = byteArrayOf()
 private val callback = object : BluetoothGattServerCallback() {
  override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
   if (newState == BluetoothProfile.STATE_DISCONNECTED) {
    synchronized(subscribers) { subscribers.remove(device.address) }
    synchronized(historyReplies) { historyReplies.remove(device.address) }
   }
  }
  override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
   if (characteristic.uuid != DATA_UUID && characteristic.uuid != JOURNAL_UUID && characteristic.uuid != IDENTITY_UUID && characteristic.uuid != HISTORY_UUID) {
    server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
    return
   }
   val bytes = when (characteristic.uuid) {
    HISTORY_UUID -> synchronized(historyReplies) { historyReplies[device.address] ?: byteArrayOf() }
    JOURNAL_UUID -> latestJournal
    IDENTITY_UUID -> (context.getSharedPreferences("watch_bridge", Context.MODE_PRIVATE).getString("device_id", "") ?: "").toByteArray(Charsets.UTF_8)
    else -> latest
   }
   if (offset > bytes.size) server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
   else server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes.copyOfRange(offset, bytes.size))
  }
  override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
   val command = if (value.size <= 32) value.toString(Charsets.UTF_8) else ""
   val match = Regex("^(heart|oxygen|steps|bia|ecg):([0-9]{1,3})$").matchEntire(command)
   val valid = characteristic.uuid == HISTORY_UUID && !preparedWrite && offset == 0 && match != null
   if (valid) {
    val kind = match!!.groupValues[1]
    val index = match.groupValues[2].toInt()
    val reply = historyRecord(kind, index).toByteArray(Charsets.UTF_8)
    synchronized(historyReplies) { historyReplies[device.address] = reply }
   }
   if (responseNeeded) server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
  }
  override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
   val enabled = synchronized(subscribers) { device.address in subscribers }
   val bytes = if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
   if (offset > bytes.size) server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
   else server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes.copyOfRange(offset, bytes.size))
  }
  override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
   val valid = descriptor.uuid == CCC_UUID && !preparedWrite && offset == 0 &&
    (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) || value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
   if (valid) synchronized(subscribers) {
    if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) subscribers.add(device.address)
    else subscribers.remove(device.address)
   }
   if (responseNeeded) server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
  }
 }
 private val advertiseCallback = object : AdvertiseCallback() {
  override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { report("BLE advertising active") }
  override fun onStartFailure(errorCode: Int) { report("BLE advertising failed: $errorCode") }
 }
 fun start() {
  if (server != null) return
  if (Build.VERSION.SDK_INT >= 31 &&
   (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
    context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)) {
   report("BLE permissions missing"); return
  }
  try {
   val adapter = manager.adapter ?: run { report("Bluetooth adapter unavailable"); return }
   if (!adapter.isEnabled || !adapter.isMultipleAdvertisementSupported) {
    report("Bluetooth disabled or BLE advertising unsupported"); return
   }
   val adv = adapter.bluetoothLeAdvertiser ?: run { report("BLE advertiser unavailable"); return }
   val gatt = manager.openGattServer(context, callback) ?: run { report("GATT server unavailable"); return }
   val characteristic = BluetoothGattCharacteristic(DATA_UUID,
    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
    BluetoothGattCharacteristic.PERMISSION_READ)
   characteristic.addDescriptor(BluetoothGattDescriptor(CCC_UUID,
    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
   val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
   service.addCharacteristic(characteristic)
   val journal = BluetoothGattCharacteristic(JOURNAL_UUID,
    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
    BluetoothGattCharacteristic.PERMISSION_READ)
   journal.addDescriptor(BluetoothGattDescriptor(CCC_UUID,
    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
   service.addCharacteristic(journal)
   service.addCharacteristic(BluetoothGattCharacteristic(HISTORY_UUID,
    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE))
   service.addCharacteristic(BluetoothGattCharacteristic(IDENTITY_UUID,
    BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
   journalCharacteristic = journal
   server = gatt
   dataCharacteristic = characteristic
   if (!gatt.addService(service)) { report("GATT addService failed"); stop(); return }
   advertiser = adv
   val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).build()
   val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID))
    .setIncludeDeviceName(false).build()
   adv.startAdvertising(settings, data, advertiseCallback)
   report("GATT service created")
  } catch (e: Exception) {
   Log.e("WatchBridgeBLE", "BLE start failed", e)
   report("BLE start failed: ${e.javaClass.simpleName}: ${e.message}")
   stop()
  }
 }
 /** Packet v1: version u8, SpO2 u8, pulse u16 LE, timestamp i64 LE. */
 fun publish(spo2: Int, pulse: Int, timestamp: Long) {
  if (spo2 !in 1..100 || pulse !in 1..300) return
  val bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
   .put(1.toByte()).put(spo2.toByte()).putShort(pulse.toShort()).putLong(timestamp).array()
  latest = bytes
  val gatt = server ?: return
  val characteristic = dataCharacteristic ?: return
  val clients = manager.getConnectedDevices(BluetoothProfile.GATT)
  val targets = synchronized(subscribers) { subscribers.toSet() }
  for (device in clients) if (device.address in targets) {
   try {
    if (Build.VERSION.SDK_INT >= 33) gatt.notifyCharacteristicChanged(device, characteristic, false, bytes)
    else {
     @Suppress("DEPRECATION")
     characteristic.value = bytes
     @Suppress("DEPRECATION")
     gatt.notifyCharacteristicChanged(device, characteristic, false)
    }
   } catch (e: Exception) { Log.w("WatchBridgeBLE", "Notify failed", e) }
  }
  report("BLE updated SpO2=$spo2 pulse=$pulse")
 }
 fun publishJournal(json: String) {
  latestJournal = json.toByteArray(Charsets.UTF_8)
  val gatt = server ?: return
  val characteristic = journalCharacteristic ?: return
  val clients = try { manager.getConnectedDevices(BluetoothProfile.GATT) } catch (_: Exception) { return }
  val targets = synchronized(subscribers) { subscribers.toSet() }
  for (device in clients) if (device.address in targets) {
   try {
    val signal = byteArrayOf(1)
    if (Build.VERSION.SDK_INT >= 33) gatt.notifyCharacteristicChanged(device, characteristic, false, signal)
    else {
     @Suppress("DEPRECATION")
     characteristic.value = signal
     @Suppress("DEPRECATION")
     gatt.notifyCharacteristicChanged(device, characteristic, false)
    }
   } catch (e: Exception) { Log.w("WatchBridgeBLE", "Journal notify failed", e) }
  }
 }
 private fun historyRecord(kind: String, index: Int): String {
  val prefs = context.getSharedPreferences("watch_bridge", Context.MODE_PRIVATE)
  if (index > 90) return JSONObject().put("done", true).toString()
  val result = try {
   if (kind == "steps") {
    val days = JSONObject(prefs.getString("step_daily_history", "{}") ?: "{}")
    val dates = days.keys().asSequence().toList().sortedDescending()
    if (index >= dates.size) null else {
     val day = dates[index]
     val entry = days.optJSONObject(day) ?: JSONObject()
     JSONObject().put("kind", "steps").put("id", "steps:$day")
      .put("date", day).put("measured_at", Instant.ofEpochMilli(entry.optLong("lastUpdate", System.currentTimeMillis())).toString())
      .put("steps", entry.optLong("steps")).put("kcal", 0).put("diatance", 0)
    }
   } else {
    val key = when (kind) {
     "heart", "oxygen" -> "vital_history_$kind"
     "bia" -> "bia_history"
     else -> "ecg_history"
    }
    val items = JSONArray(prefs.getString(key, "[]") ?: "[]")
    val entry = items.optJSONObject(index)
    if (entry == null) null else {
     val timestamp = entry.optLong(if (kind == "heart" || kind == "oxygen") "time" else "timestamp")
     if (timestamp <= 0L) null else {
      val event = JSONObject(entry.toString()).put("kind", kind)
       .put("id", "$kind:$timestamp").put("measured_at", Instant.ofEpochMilli(timestamp).toString())
      if (kind == "heart") event.put("bpm", entry.optInt("value"))
      if (kind == "oxygen") event.put("spo2", entry.optInt("value"))
      event.remove("file")
      event.remove("time")
      event.remove("value")
      event
     }
    }
   }
  } catch (e: Exception) {
   Log.w("WatchBridgeBLE", "History read failed", e)
   null
  }
  return result?.toString() ?: JSONObject().put("done", true).toString()
 }
 fun stop() {
  try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
  advertiser = null
  try { server?.close() } catch (_: Exception) {}
  server = null
  dataCharacteristic = null
  journalCharacteristic = null
  synchronized(subscribers) { subscribers.clear() }
  synchronized(historyReplies) { historyReplies.clear() }
 }
}