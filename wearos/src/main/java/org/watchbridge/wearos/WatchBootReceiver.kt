package org.watchbridge.wearos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Restart only after user has explicitly enabled background sync. */
class WatchBootReceiver : BroadcastReceiver() {
 override fun onReceive(context: Context, intent: Intent?) {
  if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
  val prefs = context.getSharedPreferences("watch_bridge", Context.MODE_PRIVATE)
  if (!prefs.getBoolean("background_sync_enabled", false)) return
  try {
   val service = Intent(context, WatchSyncService::class.java).setAction(WatchSyncService.ACTION_START)
   if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service) else context.startService(service)
  } catch (e: Exception) {
   Log.w("WatchBridgeBoot", "Background sync restart deferred until user opens app", e)
  }
 }
}
