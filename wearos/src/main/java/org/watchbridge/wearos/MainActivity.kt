package org.watchbridge.wearos

import android.Manifest
import android.health.connect.HealthConnectManager
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.HealthConnectException
import android.health.connect.datatypes.Record
import android.health.connect.datatypes.SleepSessionRecord
import android.health.connect.datatypes.HeartRateRecord
import android.health.connect.datatypes.StepsRecord
import android.health.connect.datatypes.OxygenSaturationRecord
import android.os.OutcomeReceiver
import java.time.Instant
import java.time.temporal.ChronoUnit
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Button
import android.widget.SeekBar
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.FrameLayout
import android.widget.EditText
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Read-only, on-device diagnostics. No MQTT publishing or historical data access yet. */
class MainActivity : Activity(), SensorEventListener {
 private lateinit var manager: SensorManager
 private lateinit var output: TextView
 private var heartValue: TextView? = null
 private var stepsValue: TextView? = null
 private var screen = "home"
 private val prefs by lazy { getSharedPreferences("watch_bridge", MODE_PRIVATE) }
 private data class Skin(val bg: Int, val fg: Int, val accent: Int, val tile: Int)
 private fun skin(): Skin = when(prefs.getString("skin", "dark")) {
  "light" -> Skin(0xfff6f8fb.toInt(), 0xff192432.toInt(), 0xff1777c0.toInt(), 0xffe1ebf4.toInt())
  "forest" -> Skin(0xff0d2822.toInt(), Color.WHITE, 0xff74e1ab.toInt(), 0xff1c4b3f.toInt())
  "sunset" -> Skin(0xff31182c.toInt(), Color.WHITE, 0xffffaa6c.toInt(), 0xff67314c.toInt())
  "pink" -> Skin(0xffffe8f2.toInt(), 0xff532340.toInt(), 0xffb8316f.toInt(), 0xfffac0da.toInt())
  "lavender" -> Skin(0xfff4eeff.toInt(), 0xff382a57.toInt(), 0xff704ab5.toInt(), 0xffddcff6.toInt())
  "mint" -> Skin(0xffe8fbf3.toInt(), 0xff19483a.toInt(), 0xff168969.toInt(), 0xffbdebd9.toInt())
  "cream" -> Skin(0xfffff9e8.toInt(), 0xff4e3b23.toInt(), 0xffa46828.toInt(), 0xfff6e2bb.toInt())
  "sky" -> Skin(0xffe8f6ff.toInt(), 0xff1e3c5b.toInt(), 0xff2473b8.toInt(), 0xffc4e3f7.toInt())
  else -> Skin(0xff11151f.toInt(), Color.WHITE, 0xff69bbff.toInt(), 0xff2a3345.toInt())
 }
 private fun homeButtonHeightDp(): Int = prefs.getInt("home_button_height_dp", 40).coerceIn(30, 80)
 private fun menuButtonHeightDp(): Int = prefs.getInt("menu_button_height_dp", 32).coerceIn(30, 80)
 private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
 private fun page(): LinearLayout = LinearLayout(this).apply {
  orientation = LinearLayout.VERTICAL
  gravity = Gravity.CENTER_HORIZONTAL
  setPadding(dp(18), dp(16), dp(18), dp(16))
  setBackgroundColor(skin().bg)
 }
 private fun showPage(root: LinearLayout) {
  if (screen != "home") {
   // Exact 3 physical pixels between adjacent menu items (buttons, text and fields).
   root.dividerDrawable = GradientDrawable().apply {
    setColor(Color.TRANSPARENT)
    setSize(1, 3)
   }
   root.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
  }
  setContentView(ScrollView(this).apply { setBackgroundColor(skin().bg); addView(root) })
 }
 private fun label(value: String, size: Float = 15f): TextView = TextView(this).apply {
  text = value; textSize = size; setTextColor(skin().fg)
  gravity = Gravity.CENTER; setPadding(dp(3), if (screen == "home") dp(6) else 0, dp(3), if (screen == "home") dp(6) else 0)
 }
 private fun button(title: String, action: () -> Unit): Button = Button(this).apply {
  text = title; isAllCaps = false; textSize = 11f; setTextColor(skin().fg)
  minHeight = dp(menuButtonHeightDp()); minimumHeight = dp(menuButtonHeightDp()); minWidth = 0; minimumWidth = 0
  setPadding(dp(8), dp(5), dp(8), dp(5))
  background = GradientDrawable().apply { setColor(skin().tile); cornerRadius = dp(12).toFloat() }
  setOnClickListener { action() }
 }
 private fun tile(title: String, initial: String, action: () -> Unit): Pair<LinearLayout, TextView> {
  val cell = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
   setPadding(dp(3), dp(5), dp(3), dp(5))
   background = GradientDrawable().apply { setColor(skin().tile); cornerRadius = dp(14).toFloat() }
   setOnClickListener { action() }
  }
  cell.addView(label(title, 11f).apply { setTextColor(skin().accent) })
  val value = label(initial, 18f).apply { typeface = Typeface.DEFAULT_BOLD }
  cell.addView(value)
  return cell to value
 }
 private val homeHandler = Handler(Looper.getMainLooper())
 private var pendingHomeTap: Runnable? = null
 private var pulseAnimating = false
 private var pulseLabel: TextView? = null
 private var oxygenLabel: TextView? = null
 private var homePulseButton: Button? = null
 private var homeOxygenButton: Button? = null
 private fun homeTap(kind: String, single: () -> Unit) {
  val pending = pendingHomeTap
  if (pending != null) {
   homeHandler.removeCallbacks(pending)
   pendingHomeTap = null
   if (kind == lastHomeTapKind) {
    if (kind == "heart") stopHeartBlink() else if (kind == "oxygen") stopOxygenBlink()
    showJournal(kind)
   } else single()
  } else {
   lastHomeTapKind = kind
   val task = Runnable { pendingHomeTap = null; single() }
   pendingHomeTap = task
   homeHandler.postDelayed(task, 320L)
  }
 }
 private var lastHomeTapKind = ""
 private fun transmitVital(kind: String, value: Int, time: Long = System.currentTimeMillis()) {
  val event = JSONObject().put("kind", kind).put("id", "$kind:$time")
   .put("measured_at", java.time.Instant.ofEpochMilli(time).toString())
  if (kind == "heart") event.put("bpm", value) else event.put("spo2", value)
  mqtt?.publish(kind, event.toString())
  bleServer?.publishJournal(event.toString())
 }
 private fun saveVital(kind: String, value: Int) {
  val key = "vital_history_$kind"
  val old = try { JSONArray(prefs.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
  if (old.length() > 0 && old.optJSONObject(0)?.optInt("value") == value &&
      System.currentTimeMillis() - (old.optJSONObject(0)?.optLong("time") ?: 0L) < 60_000L) return
  val updated = JSONArray().put(JSONObject().put("time", System.currentTimeMillis()).put("value", value))
  for (i in 0 until minOf(old.length(), 49)) updated.put(old.get(i))
  prefs.edit().putString(key, updated.toString()).apply()
  transmitVital(kind, value)
 }
 private var oxygenAnimating = false
 private var heartBeatGeneration = 0
 private var oxygenBeatGeneration = 0
 private fun renderHeartButton() {
  homePulseButton?.text = "♥  " + (prefs.getString("last_heart", null) ?: "—")
 }
 private fun stopHeartBlink() {
  pulseAnimating = false
  heartBeatGeneration++
  homePulseButton?.alpha = 1f
  renderHeartButton()
 }
 private fun stopOxygenBlink() {
  oxygenAnimating = false
  oxygenBeatGeneration++
  homeOxygenButton?.alpha = 1f
  homeOxygenButton?.text = "O₂  " + (prefs.getString("last_oxygen", null) ?: "—")
 }
 private fun startHomeHeart() {
  stopHeartBlink()
  pulseAnimating = true
  startSensors()
  val sensor = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
  if (sensor == null) { stopHeartBlink(); homePulseButton?.text = "♥ Нет датчика"; return }
  val generation = ++heartBeatGeneration
  val started = SystemClock.elapsedRealtime()
  val animation = object : Runnable {
   var bright = false
   override fun run() {
    if (!pulseAnimating || screen != "home" || generation != heartBeatGeneration) return
    if (SystemClock.elapsedRealtime() - started >= 15_000L) { stopHeartBlink(); return }
    bright = !bright
    homePulseButton?.alpha = if (bright) 1f else 0.35f
    homeHandler.postDelayed(this, 350L)
   }
  }
  homeHandler.post(animation)
 }
 private fun startOxygenBlink() {
  stopOxygenBlink()
  oxygenAnimating = true
  val generation = ++oxygenBeatGeneration
  val started = SystemClock.elapsedRealtime()
  val animation = object : Runnable {
   var bright = false
   override fun run() {
    if (!oxygenAnimating || screen != "home" || generation != oxygenBeatGeneration) return
    if (SystemClock.elapsedRealtime() - started >= 45_000L) { stopOxygenBlink(); return }
    bright = !bright
    homeOxygenButton?.alpha = if (bright) 1f else 0.35f
    homeHandler.postDelayed(this, 350L)
   }
  }
  homeHandler.post(animation)
 }
 private fun showHome() {
  screen = "home"; heartValue = null; stepsValue = null
  stopHeartBlink()
  stopOxygenBlink()
  val root = page().apply { setPadding(dp(10), dp(2), dp(10), dp(10)) }
  root.addView(label("Watch Bridge", 13f).apply {
   typeface = Typeface.DEFAULT_BOLD
   setPadding(dp(2), 0, dp(2), dp(2))
  })
  val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  homePulseButton = button("♥") { homeTap("heart") { startHomeHeart() } }
  homeOxygenButton = button("O₂  " + (prefs.getString("last_oxygen", null) ?: "—")) { homeTap("oxygen") { startHomeOxygen() } }
  actions.addView(homePulseButton, LinearLayout.LayoutParams(0, dp(homeButtonHeightDp()), 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
  actions.addView(homeOxygenButton, LinearLayout.LayoutParams(0, dp(homeButtonHeightDp()), 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
  root.addView(actions)
  renderHeartButton()
  val indicators = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  val stepsButton = button("♟  " + (todaySteps()?.toString() ?: "—")) { showJournal("steps") }
  val sleepButton = button("☾  —") { showJournal("sleep") }
  stepsValue = stepsButton
  indicators.addView(stepsButton, LinearLayout.LayoutParams(0, dp(homeButtonHeightDp()), 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
  indicators.addView(sleepButton, LinearLayout.LayoutParams(0, dp(homeButtonHeightDp()), 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
  root.addView(indicators)
  val measurements = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  measurements.addView(button("BIA") { showMeasurement("BIA") }, LinearLayout.LayoutParams(0, dp(homeButtonHeightDp()), 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
  measurements.addView(button("ЭКГ") { showMeasurement("ECG") }, LinearLayout.LayoutParams(0, dp(homeButtonHeightDp()), 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
  root.addView(measurements)
  root.addView(button("Настройки") { showSettings() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(menuButtonHeightDp())).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
  showPage(root)
 }
 private fun startHomeOxygen() {
  startOxygenBlink()
  startSpO2Measurement()
 }
 private fun stepHistory(): JSONObject = try {
  JSONObject(prefs.getString("step_daily_history", "{}") ?: "{}")
 } catch (_: Exception) { JSONObject() }
 private fun todaySteps(): Int? = stepHistory().optJSONObject(LocalDate.now().toString())?.optInt("steps")
 private fun recordStepCounter(total: Float) {
  if (!total.isFinite() || total < 0f) return
  val day = LocalDate.now().toString()
  val history = stepHistory()
  val entry = history.optJSONObject(day) ?: JSONObject()
  val current = total.toLong()
  val previous = entry.optLong("lastCounter", -1L)
  val increment = if (previous < 0L) 0L else if (current >= previous) current - previous else current
  val updated = (entry.optLong("steps", 0L) + increment).coerceAtMost(Int.MAX_VALUE.toLong())
  entry.put("steps", updated)
  entry.put("lastCounter", current)
  entry.put("lastUpdate", System.currentTimeMillis())
  history.put(day, entry)
  history.keys().asSequence().toList().sortedDescending().drop(90).forEach { history.remove(it) }
  prefs.edit().putString("step_daily_history", history.toString()).apply()
  if (increment > 0L) {
   val event = JSONObject().put("kind", "steps").put("id", "steps:$day")
    .put("measured_at", java.time.Instant.now().toString()).put("date", day)
    .put("steps", updated).put("diatance", 0).put("kcal", 0)
   mqtt?.publish("steps", event.toString())
   bleServer?.publishJournal(event.toString())
  }
 }
 private fun publishCurrentSteps() {
  val day = LocalDate.now().toString()
  val entry = stepHistory().optJSONObject(day)
  if (entry == null) {
   mqttInfo = "Шаги: нет локальных данных"
   return
  }
  val event = JSONObject().put("kind", "steps").put("id", "steps:$day")
   .put("measured_at", java.time.Instant.now().toString()).put("date", day)
   .put("steps", entry.optLong("steps", 0L)).put("diatance", 0).put("kcal", 0)
  mqtt?.publish("steps", event.toString())
  bleServer?.publishJournal(event.toString())
 }
 private fun showJournal(kind: String) {
  stopHeartBlink()
  stopOxygenBlink()
  screen = kind
  val root = page().apply { setPadding(dp(12), dp(2), dp(12), dp(12)) }
  root.addView(button("← Назад") { showHome() })
  val title = when(kind) { "heart" -> "История пульса"; "oxygen" -> "История кислорода"; "steps" -> "История шагов"; else -> "История сна" }
  root.addView(label(title, 17f))
  if (kind == "heart" || kind == "oxygen") {
   val history = try { JSONArray(prefs.getString("vital_history_$kind", "[]")) } catch (_: Exception) { JSONArray() }
   if (history.length() == 0) root.addView(label("Измерений пока нет", 12f))
   for (i in 0 until history.length()) {
    val entry = history.optJSONObject(i) ?: continue
    val date = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(entry.optLong("time")))
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    row.addView(label("$date   ${entry.optInt("value")} " + (if (kind == "heart") "уд/мин" else "%"), 12f),
     LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(row)
   }
  } else if (kind == "steps") {
   root.addView(button("Отправить шаги по MQTT") { publishCurrentSteps(); showJournal("steps") })
   root.addView(label("Шаги, зарегистрированные приложением по дням", 11f))
   val history = stepHistory()
   val days = history.keys().asSequence().toList().sortedDescending()
   if (days.isEmpty()) root.addView(label("Данных пока нет", 12f))
   days.forEach { day ->
    val entry = history.optJSONObject(day) ?: return@forEach
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    row.addView(label("$day   ${entry.optInt("steps")} шагов", 12f),
     LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(row)
   }
   root.addView(label("Учёт начинается с первого запуска. Для точного суточного итога нужна синхронизация с системной историей шагов.", 10f))
  } else root.addView(label("История пока не подключена", 12f))
  showPage(root)
 }
 private fun showSettings() {
  screen = "settings"
  val root = page().apply { setPadding(dp(10), dp(2), dp(10), dp(10)) }
  root.addView(label("Настройки", 13f).apply {
   typeface = Typeface.DEFAULT_BOLD
   setPadding(dp(2), 0, dp(2), 0)
  })
  fun settingsButton(title: String, action: () -> Unit) {
   root.addView(button(title, action), LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT, dp(menuButtonHeightDp())
   ).apply { setMargins(dp(1), 0, dp(1), 0) })
  }
  settingsButton("Настройки приложения") { showAppSettings() }
  settingsButton("Параметры тела") { showBodySettings() }
  settingsButton("MQTT / Home Assistant") { showMqttSettings() }
  settingsButton("Bluetooth") { showBluetoothSettings() }
  settingsButton("О приложении") { showAbout() }
  settingsButton("Назад") { showHome() }
  showPage(root)
 }
 private fun showAbout() {
  screen = "about"
  val root = page().apply { setPadding(dp(10), dp(2), dp(10), dp(10)) }
  root.addView(label("О приложении", 13f).apply {
   typeface = Typeface.DEFAULT_BOLD
   setPadding(dp(2), 0, dp(2), 0)
  })
  val version = try {
   val info = packageManager.getPackageInfo(packageName, 0)
   info.versionName ?: "—"
  } catch (_: Exception) { "—" }
  root.addView(label("Watch Bridge", 16f).apply { typeface = Typeface.DEFAULT_BOLD })
  root.addView(label("Версия: $version", 12f))
  root.addView(label("Приложение для умных часов Wear OS", 12f))
  root.addView(label("Измерения и история показателей здоровья. Передача данных в Home Assistant через Bluetooth LE или MQTT.", 11f))
  root.addView(label("Разработчик: Kusheev Sergei", 11f))
  root.addView(button("Назад") { showSettings() }, LinearLayout.LayoutParams(
   LinearLayout.LayoutParams.MATCH_PARENT, dp(menuButtonHeightDp())
  ).apply { setMargins(dp(1), 0, dp(1), 0) })
  showPage(root)
 }
 private fun showAppSettings() {
  screen = "app_settings"
  val root = page().apply { setPadding(dp(10), dp(2), dp(10), dp(10)) }
  root.addView(label("Настройки приложения", 13f).apply {
   typeface = Typeface.DEFAULT_BOLD
   setPadding(dp(2), 0, dp(2), 0)
  })
  root.addView(label("Тема оформления", 11f).apply {
   setPadding(dp(2), 0, dp(2), 0)
  })
  val themeKeys = listOf("dark", "light", "forest", "sunset", "pink", "lavender", "mint", "cream", "sky")
  val themeNames = themeKeys.map { themeTitle(it) }
  val selector = Spinner(this).apply {
   val palette = skin()
   adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, themeNames) {
    override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
     return (super.getView(position, convertView, parent) as TextView).apply {
      setTextColor(palette.fg)
      setBackgroundColor(palette.tile)
      textSize = 13f
      setPadding(dp(10), dp(8), dp(8), dp(8))
     }
    }
    override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
     return (super.getDropDownView(position, convertView, parent) as TextView).apply {
      setTextColor(palette.fg)
      setBackgroundColor(palette.tile)
      textSize = 13f
      setPadding(dp(10), dp(10), dp(8), dp(10))
     }
    }
   }
   setPopupBackgroundDrawable(GradientDrawable().apply { setColor(palette.tile); cornerRadius = dp(10).toFloat() })
   background = GradientDrawable().apply { setColor(palette.tile); cornerRadius = dp(10).toFloat() }
   setSelection(themeKeys.indexOf(prefs.getString("skin", "dark")).coerceAtLeast(0))
   onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
    override fun onNothingSelected(parent: AdapterView<*>?) {}
    override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
     val selected = themeKeys[position]
     if (prefs.getString("skin", "dark") != selected) {
      prefs.edit().putString("skin", selected).apply()
      showAppSettings()
     }
    }
   }
  }
  root.addView(selector, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).apply {
   setMargins(dp(1), 0, dp(1), 0)
  })
  fun heightControl(title: String, key: String, defaultHeight: Int) {
   val current = prefs.getInt(key, defaultHeight).coerceIn(30, 80)
   val line = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
   }
   val name = label(title, 11f).apply {
    gravity = Gravity.CENTER_VERTICAL or Gravity.START
    setPadding(dp(2), 0, 0, 0)
    isSingleLine = true
   }
   val value = label("$current dp", 11f).apply {
    gravity = Gravity.CENTER_VERTICAL or Gravity.END
    setPadding(0, 0, dp(2), 0)
    isSingleLine = true
   }
   line.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
   line.addView(value)
   root.addView(line, LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
   ).apply { bottomMargin = 0 })
   val slider = SeekBar(this).apply {
    max = 50
    progress = current - 30
    progressTintList = android.content.res.ColorStateList.valueOf(skin().accent)
    thumbTintList = android.content.res.ColorStateList.valueOf(skin().accent)
    setPadding(dp(4), 0, dp(4), 0)
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
     override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
      value.text = "${progress + 30} dp"
     }
     override fun onStartTrackingTouch(seekBar: SeekBar?) {}
     override fun onStopTrackingTouch(seekBar: SeekBar?) {
      prefs.edit().putInt(key, (progress + 30).coerceIn(30, 80)).apply()
      showAppSettings()
     }
    })
   }
   root.addView(slider, LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT, dp(30)
   ).apply { bottomMargin = 0 })
  }
  heightControl("Главный экран", "home_button_height_dp", 40)
  heightControl("Кнопки меню", "menu_button_height_dp", 32)
  fun historyToggle(title: String, key: String) {
   var enabled = prefs.getBoolean(key, false)
   lateinit var control: Button
   control = button("$title: " + if (enabled) "Вкл" else "Выкл") {
    enabled = !enabled
    prefs.edit().putBoolean(key, enabled).apply()
    control.text = "$title: " + if (enabled) "Вкл" else "Выкл"
   }
   root.addView(control, LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT, dp(menuButtonHeightDp())
   ))
  }
  historyToggle("Отправка всей истории", "history_send_button_enabled")
  historyToggle("Удаление записей", "history_delete_button_enabled")
  historyToggle("Автосбор пульса", "auto_heart_collection")
  var backgroundEnabled = prefs.getBoolean("background_sync_enabled", false)
  lateinit var backgroundToggle: Button
  backgroundToggle = button("Фоновый сбор и MQTT: " + if (backgroundEnabled) "Вкл" else "Выкл") {
   backgroundEnabled = !backgroundEnabled
   prefs.edit().putBoolean("background_sync_enabled", backgroundEnabled).apply()
   updateBackgroundService()
   backgroundToggle.text = "Фоновый сбор и MQTT: " + if (backgroundEnabled) "Вкл" else "Выкл"
  }
  root.addView(backgroundToggle)
  root.addView(button("По умолчанию: 40 / 32 dp") {
   prefs.edit().putInt("home_button_height_dp", 40)
    .putInt("menu_button_height_dp", 32).apply()
   showAppSettings()
  }, LinearLayout.LayoutParams(
   LinearLayout.LayoutParams.MATCH_PARENT, dp(menuButtonHeightDp())
  ).apply { bottomMargin = 0 })
  root.addView(button("Назад") { showSettings() }, LinearLayout.LayoutParams(
   LinearLayout.LayoutParams.MATCH_PARENT, dp(menuButtonHeightDp())
  ))
  showPage(root)
 }
 private fun showMqttSettings() {
  screen = "mqtt_settings"
  val root = page()
  root.addView(label("MQTT / Home Assistant", 15f))
  root.addView(label("Отправка измерений в Home Assistant через MQTT", 11f))
  root.addView(label("В очереди: ${mqtt?.pendingCount() ?: 0} записей", 11f))
  val delivered = mqtt?.lastDelivered() ?: 0L
  root.addView(label("Последняя отправка брокеру: " + if (delivered > 0) SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(delivered)) else "нет в этом сеансе", 11f))
  fun field(title: String, key: String, fallback: String = "", password: Boolean = false): EditText {
   root.addView(label(title, 11f))
   val input = EditText(this).apply {
    setText(prefs.getString(key, fallback).orEmpty())
    setTextColor(skin().fg)
    textSize = 12f
    setSingleLine(true)
    if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
   }
   root.addView(input)
   return input
  }
  var enabled = prefs.getBoolean("mqtt_enabled", false)
  var haEnabled = prefs.getBoolean("ha_mqtt_enabled", true)
  lateinit var enabledToggle: Button
  enabledToggle = button("Отправлять в MQTT: " + if (enabled) "Да" else "Нет") {
   enabled = !enabled
   enabledToggle.text = "Отправлять в MQTT: " + if (enabled) "Да" else "Нет"
  }
  root.addView(enabledToggle)
  lateinit var haToggle: Button
  haToggle = button("Дополнительно в Home Assistant: " + if (haEnabled) "Да" else "Нет") {
   haEnabled = !haEnabled
   haToggle.text = "Дополнительно в Home Assistant: " + if (haEnabled) "Да" else "Нет"
  }
  root.addView(haToggle)
  root.addView(label("MQTT: watchbridge/<device>/history; HA: дополнительно rogbid/<device>/history", 10f))
  val host = field("MQTT сервер (IP или DNS)", "mqtt_host")
  val port = field("Порт (TLS: 8883, обычный: 1883)", "mqtt_port", "8883")
  val user = field("Логин", "mqtt_user")
  val pass = field("Пароль", "mqtt_password", password = true)
  var tls = prefs.getBoolean("mqtt_tls", true)
  lateinit var tlsToggle: Button
  tlsToggle = button("TLS: " + if (tls) "Вкл" else "Выкл") {
   tls = !tls
   tlsToggle.text = "TLS: " + if (tls) "Вкл" else "Выкл"
  }
  root.addView(tlsToggle)
  val notice = label(mqttInfo, 11f)
  root.addView(notice)
  fun save(): Boolean {
   val p = port.text.toString().toIntOrNull()
   if (p == null || p !in 1..65535) {
    mqttInfo = "Неверный MQTT порт"
    notice.text = mqttInfo
    return false
   }
   prefs.edit().putString("mqtt_host", host.text.toString().trim())
    .putString("mqtt_port", p.toString())
    .putString("mqtt_user", user.text.toString().trim())
    .putString("mqtt_password", pass.text.toString())
    .putBoolean("mqtt_enabled", enabled)
    .putBoolean("ha_mqtt_enabled", haEnabled)
    .putBoolean("mqtt_tls", tls).apply()
   mqttInfo = "Настройки сохранены"
   notice.text = mqttInfo
   return true
  }
  root.addView(button("Сохранить") { save() })
  root.addView(button("Повторить отправку очереди") { mqtt?.retryPending(); showMqttSettings() })
  root.addView(button("Проверить MQTT") {
   if (save()) {
    if (enabled) {
     mqttInfo = "MQTT: проверка…"
     notice.text = mqttInfo
     mqtt?.testConnection()
    } else {
     mqttInfo = "Включи отправку в Home Assistant для проверки"
     notice.text = mqttInfo
    }
   }
  })
  root.addView(button("Назад") { showSettings() })
  showPage(root)
 }
 private fun showBluetoothSettings() {
  screen = "bluetooth_settings"
  val root = page()
  root.addView(label("Bluetooth", 16f))
  root.addView(label("Передача данных по Bluetooth независимо от MQTT", 11f))
  var enabled = prefs.getBoolean("ble_enabled", false)
  lateinit var toggle: Button
  toggle = button("Передавать по Bluetooth: " + if (enabled) "Да" else "Нет") {
   enabled = !enabled
   toggle.text = "Передавать по Bluetooth: " + if (enabled) "Да" else "Нет"
  }
  root.addView(toggle)
  root.addView(label("Состояние Bluetooth", 12f))
  root.addView(label(bleInfo, 11f))
  root.addView(button("Сохранить") {
   prefs.edit().putBoolean("ble_enabled", enabled).apply()
   if (enabled) startBle() else { bleServer?.stop(); bleServer = null }
   showBluetoothSettings()
  })
  root.addView(button("Назад") { showSettings() })
  showPage(root)
 }
 private fun showBodySettings() {
  screen = "body_settings"
  val root = page()
  root.addView(label("Параметры тела", 18f))
  root.addView(label("Для расчёта состава тела (BIA)", 12f))
  fun field(title: String, key: String, decimal: Boolean): EditText {
   root.addView(label(title, 12f))
   val input = EditText(this).apply {
    setText(prefs.getString(key, "") ?: "")
    textSize = 16f
    setTextColor(skin().fg)
    setHintTextColor(skin().accent)
    gravity = Gravity.CENTER
    inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else InputType.TYPE_CLASS_NUMBER
    setSingleLine(true)
    background = GradientDrawable().apply { setColor(skin().tile); cornerRadius = dp(10).toFloat() }
    setPadding(dp(8), dp(7), dp(8), dp(7))
   }
   root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(43)))
   return input
  }
  val height = field("Рост (см)", "body_height", true)
  val weight = field("Вес (кг)", "body_weight", true)
  val birthDate = label(prefs.getString("body_birth_date", null) ?: "Выбрать дату рождения", 14f)
  root.addView(label("Дата рождения", 12f))
  root.addView(button(prefs.getString("body_birth_date", null) ?: "Выбрать дату рождения") {
   val existing = parseBirthDate(prefs.getString("body_birth_date", null)) ?: LocalDate.now().minusYears(30)
   DatePickerDialog(this, { _, year, month, day ->
    val selected = LocalDate.of(year, month + 1, day)
    birthDate.text = selected.format(DateTimeFormatter.ISO_LOCAL_DATE)
   }, existing.year, existing.monthValue - 1, existing.dayOfMonth).apply {
    datePicker.maxDate = System.currentTimeMillis()
   }.show()
  })
  root.addView(birthDate)
  root.addView(label("Пол для алгоритма BIA", 12f))
  val gender = Spinner(this).apply {
   adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Мужской", "Женский"))
   setSelection(if (prefs.getInt("body_gender", 1) == 0) 1 else 0)
  }
  root.addView(gender)
  val notice = label("", 12f)
  root.addView(notice)
  root.addView(button("Сохранить") {
   val h = height.text.toString().replace(',', '.').toFloatOrNull()
   val w = weight.text.toString().replace(',', '.').toFloatOrNull()
   val birthday = parseBirthDate(birthDate.text.toString())
   val a = birthday?.let { Period.between(it, LocalDate.now()).years }
   if (h == null || h !in 100f..250f || w == null || w !in 20f..300f || a == null || a !in 18..120) {
    notice.text = "Проверь данные: рост 100–250 см, вес 20–300 кг, дата рождения (возраст 18–120 лет)"
   } else {
    prefs.edit()
     .putString("body_height", h.toString())
     .putString("body_weight", w.toString())
     .putString("body_birth_date", birthday.toString())
     .remove("body_age")
     .putInt("body_gender", if (gender.selectedItemPosition == 0) 1 else 0)
     .apply()
    notice.text = "Сохранено"
   }
  })
  root.addView(button("Назад") { showSettings() })
  showPage(root)
 }
 private fun themeTitle(name: String): String = when (name) {
  "dark" -> "Тёмная"
  "light" -> "Светлая"
  "forest" -> "Лесная"
  "sunset" -> "Закат"
  "pink" -> "Розовая"
  "lavender" -> "Лавандовая"
  "mint" -> "Мятная"
  "cream" -> "Кремовая"
  "sky" -> "Небесная"
  else -> name
 }
 private var progressBar: ProgressBar? = null
 private var progressLabel: TextView? = null
 private var measurementStatus: TextView? = null
 private var measurementResult: TextView? = null
 private var measurementProgress = 0
 private var measurementMessage = "Ожидание"
 private var measurementPreview = ""
 private var biaSavedForRun = false
 private var ecgWaveform: EcgWaveformView? = null
 private var ecgRecording = false
 private var ecgStartedAt = 0L
 private var ecgContactSince = 0L
 private var ecgSessionId = 0L
 private var ecgContactLost = false
 private val ecgPreparation = ArrayDeque<Float>()
 private var ecgPreparationWarning = ""
 private var ecgLastUiAt = 0L
 private var ecgLastRenderedSample = 0
 private val ecgSamples = mutableListOf<Triple<Long, Float, Int>>()
 private val ecgHandler = Handler(Looper.getMainLooper())
 private fun showDiagnostics() {
  screen = "diagnostics"
  val root = page()
  root.addView(label("Измерения", 19f))
  root.addView(button("♥ Пульс") { showMeasurement("HEART_RATE") })
  root.addView(button("O₂ Кислород") { showMeasurement("SPO2") })
  root.addView(button("ЭКГ") { showMeasurement("ECG") })
  root.addView(button("Состав тела · BIA") { showMeasurement("BIA") })
  root.addView(button("История состава тела") { showBiaHistory() })
  root.addView(button("Назад") { showSettings() })
  showPage(root)
 }
 private fun showMeasurement(tracker: String) {
  screen = "measurement"
  activeTrackerName = tracker
  measurementProgress = 0
  measurementMessage = ""
  measurementPreview = ""
  val root = page().apply { setPadding(dp(10), dp(2), dp(10), dp(10)) }
  val title = when (tracker) {
   "BIA" -> "Состав тела"
   "ECG" -> "ЭКГ"
   "SPO2" -> "Кислород"
   else -> "Пульс"
  }
  root.addView(label(title, 13f).apply {
   typeface = Typeface.DEFAULT_BOLD
   setPadding(dp(2), 0, dp(2), dp(2))
  })
  if (tracker == "BIA" && bodyProfile() == null) {
   root.addView(label("Укажи параметры тела в настройках", 11f))
  }
  progressLabel = label("0%", 24f).apply {
   typeface = Typeface.DEFAULT_BOLD
   setPadding(dp(2), dp(1), dp(2), dp(1))
  }
  progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
   max = 100
   progress = 0
   progressTintList = android.content.res.ColorStateList.valueOf(skin().accent)
   setPadding(0, 0, 0, 0)
  }
  measurementStatus = label("", 13f).apply { setPadding(dp(2), dp(1), dp(2), dp(1)) }
  measurementResult = label("", 12f).apply { setPadding(dp(2), dp(1), dp(2), dp(1)) }
  if (tracker == "ECG") {
   val chart = FrameLayout(this)
   ecgWaveform = EcgWaveformView(this)
   chart.addView(ecgWaveform, FrameLayout.LayoutParams(
    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
   progressLabel?.apply {
    setTextColor(skin().fg)
    setShadowLayer(dp(3).toFloat(), 0f, 0f, skin().bg)
    chart.addView(this, FrameLayout.LayoutParams(
     FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
   }
   chart.addView(progressBar, FrameLayout.LayoutParams(
    FrameLayout.LayoutParams.MATCH_PARENT, dp(8), Gravity.BOTTOM).apply {
     setMargins(dp(8), 0, dp(8), dp(2))
   })
   root.addView(chart, LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT, dp(105)))
   root.addView(measurementStatus)
  } else {
   root.addView(progressLabel)
   root.addView(progressBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)).apply {
    setMargins(dp(10), 0, dp(10), dp(2))
   })
   root.addView(measurementStatus)
  }
  val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  actions.addView(button("Начать") {
   measurementProgress = 0
   measurementMessage = "Ожидание данных датчика…"
   measurementPreview = ""
   if (tracker == "ECG") startEcgRecording()
   else if (tracker == "SPO2") startSpO2Measurement()
   else startSamsungTrackerTest(tracker)
   updateMeasurementUi()
  }, LinearLayout.LayoutParams(0, dp(menuButtonHeightDp()), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
  if (tracker == "BIA" || tracker == "ECG") {
   actions.addView(button("История") {
    if (tracker == "ECG") { if (ecgRecording) finishEcgRecording() else stopSamsungTrackerTest(); showEcgHistory() }
    else { stopSamsungTrackerTest(); showBiaHistory() }
   }, LinearLayout.LayoutParams(0, dp(menuButtonHeightDp()), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
  }
  root.addView(actions)
  root.addView(button("Назад") {
   if (tracker == "ECG") {
    if (ecgRecording) finishEcgRecording() else stopSamsungTrackerTest()
   } else stopSamsungTrackerTest()
   progressBar = null
   progressLabel = null
   measurementStatus = null
   measurementResult = null
   ecgWaveform = null
   showHome()
  })
  // Keep BIA results below the Back button; ECG diagnostics stay below the chart.
  root.addView(measurementResult)
  showPage(root)
  updateMeasurementUi()
 }
 private fun startEcgRecording() {
  stopSamsungTrackerTest()
  synchronized(ecgSamples) { ecgSamples.clear() }
  ecgWaveform?.clear()
  ecgSessionId++
  ecgRecording = true
  ecgStartedAt = 0L // Wait for five seconds of continuous valid electrode contact.
  ecgContactSince = 0L
  ecgContactLost = false
  ecgPreparation.clear()
  ecgPreparationWarning = ""
  ecgLastUiAt = 0L
  ecgLastRenderedSample = 0
  measurementProgress = 0
  measurementMessage = "Прикоснись пальцем к электроду"
  measurementPreview = "Ожидание устойчивого контакта…"
  updateMeasurementUi()
  startSamsungTrackerTest("ECG")
 }
 private fun receiveEcgSample(value: Float, contact: Int, timestamp: Long) {
  if (!ecgRecording || !value.isFinite()) return
  val now = SystemClock.elapsedRealtime()
  if (ecgStartedAt == 0L) {
   if (contact == 0) {
    if (ecgContactSince == 0L) ecgContactSince = now
    ecgPreparation.addLast(value)
    while (ecgPreparation.size > 2500) ecgPreparation.removeFirst()
    val stableFor = now - ecgContactSince
    if (stableFor >= 5000L && ecgPreparation.size >= 1500) {
     val recent = ecgPreparation.toList()
     val segment = (recent.size / 5).coerceAtLeast(1)
     val firstMean = recent.take(segment).average()
     val lastMean = recent.takeLast(segment).average()
     val drift = kotlin.math.abs(lastMean - firstMean)
     val localVariation = recent.zipWithNext().map { kotlin.math.abs(it.second - it.first).toDouble() }.average()
     val acceptable = drift < kotlin.math.max(1.5, localVariation * 15.0)
     if (acceptable || stableFor >= 15_000L) {
      ecgPreparationWarning = if (acceptable) "" else "Сигнал нестабилен: высокий дрейф"
      ecgStartedAt = now
      ecgLastUiAt = 0L
     ecgLastRenderedSample = 0
     synchronized(ecgSamples) { ecgSamples.clear() }
     val session = ecgSessionId
     ecgHandler.postDelayed({
      if (ecgRecording && ecgSessionId == session && ecgStartedAt != 0L &&
          SystemClock.elapsedRealtime() - ecgStartedAt >= 29_500L) finishEcgRecording()
     }, 30_000L)
     } else ecgPreparationWarning = "Сигнал нестабилен: ожидание до 15 с"
    }
   } else {
    ecgContactSince = 0L
    ecgPreparation.clear()
    ecgPreparationWarning = ""
   }
   if (ecgStartedAt == 0L) {
    if (now - ecgLastUiAt >= 200L) {
     ecgLastUiAt = now
     runOnUiThread {
      if (ecgRecording && ecgStartedAt == 0L && screen == "measurement") {
       measurementProgress = 0
       measurementMessage = if (contact == 0) "Проверка контакта электрода…" else "Прикоснись пальцем к электроду"
       measurementPreview = if (contact == 0 && ecgContactSince != 0L)
        "Контакт: ${((now - ecgContactSince) / 1000L).coerceIn(0L, 15L)} с · $ecgPreparationWarning"
       else "Отсчёт начнётся после 5 секунд устойчивого контакта"
       updateMeasurementUi()
      }
     }
    }
    return
   }
  }
  val elapsed = now - ecgStartedAt
  if (elapsed < 0 || elapsed > 30_500L) return
  synchronized(ecgSamples) {
   if (ecgSamples.size < 30000) ecgSamples.add(Triple(timestamp, value, contact))
  }
  if (contact != 0) ecgContactLost = true
  if (now - ecgLastUiAt < 100L) return
  ecgLastUiAt = now
  runOnUiThread {
   if (!ecgRecording || screen != "measurement" || activeTrackerName != "ECG") return@runOnUiThread
   val batch = synchronized(ecgSamples) {
    val start = ecgLastRenderedSample.coerceAtMost(ecgSamples.size)
    val values = ecgSamples.subList(start, ecgSamples.size).map { it.second }
    ecgLastRenderedSample = ecgSamples.size
    values
   }
   ecgWaveform?.appendBatch(batch)
   measurementProgress = (elapsed / 300L).toInt().coerceIn(0, 100)
   measurementMessage = if (contact == 0) "Запись сигнала…" else "Проверь контакт электрода (код $contact)"
   measurementPreview = "Отсчётов: ${synchronized(ecgSamples) { ecgSamples.size }}" +
    if (ecgPreparationWarning.isNotEmpty()) " · $ecgPreparationWarning" else ""
   updateMeasurementUi()
  }
 }
 /**
  * Experimental ECG beat timing extractor, not a diagnostic R-peak detector.
  * Removes a moving baseline and uses a robust amplitude threshold plus
  * a 280 ms refractory period. Timestamps are derived from sample positions
  * to avoid relying on undocumented vendor timestamp units.
  */
 private fun detectEcgBeats(samples: List<Triple<Long, Float, Int>>, durationMs: Long): List<Int> {
  if (samples.size < 100 || durationMs < 1000L) return emptyList()
  val valid = samples.map { if (it.third == 0 && it.second.isFinite()) it.second.toDouble() else Double.NaN }
  val window = (samples.size.toDouble() * 0.18 / (durationMs / 1000.0)).toInt().coerceIn(3, 300)
  val prefix = DoubleArray(valid.size + 1)
  val counts = IntArray(valid.size + 1)
  valid.forEachIndexed { i, v ->
   prefix[i + 1] = prefix[i] + if (v.isFinite()) v else 0.0
   counts[i + 1] = counts[i] + if (v.isFinite()) 1 else 0
  }
  val deviations = DoubleArray(valid.size) { i ->
   if (!valid[i].isFinite()) 0.0 else {
    val lo = (i - window / 2).coerceAtLeast(0)
    val hi = (i + window / 2 + 1).coerceAtMost(valid.size)
    val count = counts[hi] - counts[lo]
    if (count == 0) 0.0 else kotlin.math.abs(valid[i] - (prefix[hi] - prefix[lo]) / count)
   }
  }
  val sorted = deviations.filter { it > 0.0 && it.isFinite() }.sorted()
  if (sorted.size < 50) return emptyList()
  val median = sorted[sorted.size / 2]
  val high = sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.lastIndex)]
  val threshold = kotlin.math.max(median * 3.0, high * 0.5)
  val refractory = (samples.size.toDouble() * 0.28 / (durationMs / 1000.0)).toInt().coerceAtLeast(1)
  val beats = mutableListOf<Int>()
  var candidate = -1
  for (i in 1 until deviations.lastIndex) {
   if (deviations[i] < threshold || deviations[i] < deviations[i - 1] || deviations[i] < deviations[i + 1]) continue
   if (candidate < 0) {
    candidate = i
   } else if (i - candidate < refractory) {
    if (deviations[i] > deviations[candidate]) candidate = i
   } else {
    beats.add((candidate.toDouble() * durationMs / samples.size).toInt())
    candidate = i
   }
  }
  if (candidate >= 0) beats.add((candidate.toDouble() * durationMs / samples.size).toInt())
  return beats
 }
 private fun finishEcgRecording() {
  if (!ecgRecording) { stopSamsungTrackerTest(); return }
  ecgRecording = false
  ecgSessionId++
  val durationMs = if (ecgStartedAt == 0L) 0L else (SystemClock.elapsedRealtime() - ecgStartedAt).coerceAtLeast(0L)
  stopSamsungTrackerTest()
  val copy = synchronized(ecgSamples) { ecgSamples.toList() }
  if (copy.isNotEmpty()) {
   try {
    val fileName = "ecg_${System.currentTimeMillis()}.csv"
    openFileOutput(fileName, MODE_PRIVATE).bufferedWriter().use { writer ->
     writer.write("timestamp,ecg_mv,lead_off\n")
     copy.forEach { (timestamp, mv, lead) -> writer.write("$timestamp,${kotlin.math.round(mv.toDouble() * 1000.0) / 1000.0},$lead\n") }
    }
    val history = try { JSONArray(prefs.getString("ecg_history", "[]")) } catch (_: Exception) { JSONArray() }
    val item = JSONObject().put("file", fileName).put("timestamp", System.currentTimeMillis())
     .put("durationMs", durationMs).put("samples", copy.size).put("contactLost", ecgContactLost)
    val updated = JSONArray().put(item)
    for (i in 0 until minOf(19, history.length())) updated.put(history.get(i))
    prefs.edit().putString("ecg_history", updated.toString()).apply()
    val recordedAt = item.optLong("timestamp")
    val event = JSONObject(item.toString()).put("kind", "ecg").put("id", "ecg:$recordedAt")
     .put("measured_at", java.time.Instant.ofEpochMilli(recordedAt).toString())
    event.remove("file")
    mqtt?.publish("ecg", event.toString())
    bleServer?.publishJournal(event.toString())
    // Transmit only experimental R-peak timing markers, never raw ECG waveform.
    // The complete signal remains available in the local CSV export.
    val beats = detectEcgBeats(copy, durationMs)
    val beatEvent = JSONObject().put("kind", "ecg_beats")
     .put("id", "ecg:$recordedAt:beats")
     .put("session_id", "ecg:$recordedAt")
     .put("measured_at", java.time.Instant.ofEpochMilli(recordedAt).toString())
     .put("algorithm", "adaptive_peak_v1_experimental")
     .put("beat_offsets_ms", JSONArray(beats))
     .put("beat_count", beats.size)
    mqtt?.publish("ecg_beats", beatEvent.toString())
    measurementMessage = "Запись ЭКГ сохранена"
    measurementPreview = "${durationMs / 1000} с · ${copy.size} отсчётов" +
     if (ecgContactLost) " · Были нарушения контакта" else ""
   } catch (e: Exception) {
    measurementMessage = "Ошибка сохранения ЭКГ"
    measurementPreview = e.message ?: e.javaClass.simpleName
   }
  } else {
   measurementMessage = "Нет отсчётов ЭКГ"
   measurementPreview = "Проверь контакт электрода"
  }
  measurementProgress = (durationMs / 300L).toInt().coerceIn(0, 100)
  updateMeasurementUi()
 }
 private fun sendMeasurementHistory(kind: String) {
  var count = 0
  val history = if (kind == "bia") normalizedBiaHistory() else
   try { JSONArray(prefs.getString("ecg_history", "[]")) } catch (_: Exception) { JSONArray() }
  for (i in history.length() - 1 downTo 0) {
   val entry = history.optJSONObject(i) ?: continue
   val timestamp = entry.optLong("timestamp", 0L)
   if (timestamp <= 0L) continue
   val event = JSONObject(entry.toString()).put("kind", kind).put("id", "$kind:$timestamp")
    .put("measured_at", java.time.Instant.ofEpochMilli(timestamp).toString())
   if (kind == "ecg") event.remove("file") // Raw ECG waveform is not transmitted.
   mqtt?.publish(kind, event.toString())
   bleServer?.publishJournal(event.toString())
   count++
  }
  android.widget.Toast.makeText(this, "В очередь отправки: $count записей", android.widget.Toast.LENGTH_LONG).show()
 }
 private fun confirmDeleteMeasurement(kind: String, timestamp: Long) {
  AlertDialog.Builder(this).setTitle("Удалить запись?")
   .setMessage(if (kind == "ecg") "Удалить запись и локальный CSV-файл ЭКГ?" else "Удалить локальную запись BIA?")
   .setNegativeButton("Отмена", null)
   .setPositiveButton("Удалить") { _, _ ->
    if (!prefs.getBoolean("history_delete_button_enabled", false)) return@setPositiveButton
    val key = if (kind == "ecg") "ecg_history" else "bia_history"
    val history = if (kind == "bia") normalizedBiaHistory() else
     try { JSONArray(prefs.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
    val updated = JSONArray()
    for (i in 0 until history.length()) {
     val item = history.optJSONObject(i) ?: continue
     if (item.optLong("timestamp") == timestamp) {
      if (kind == "ecg") {
       val file = item.optString("file")
       if (Regex("^ecg_[0-9]+\\.csv$").matches(file)) deleteFile(file)
      }
     } else updated.put(item)
    }
    prefs.edit().putString(key, updated.toString()).apply()
    if (kind == "ecg") showEcgHistory() else showBiaHistory()
   }.show()
 }
 private fun showEcgHistory() {
  screen = "ecg_history"
  val root = page().apply { setPadding(dp(10), dp(2), dp(10), dp(10)) }
  root.addView(label("История ЭКГ", 13f).apply {
   typeface = Typeface.DEFAULT_BOLD
   setPadding(dp(2), 0, dp(2), dp(2))
  })
  root.addView(button("← Назад") { showMeasurement("ECG") })
  if (prefs.getBoolean("history_send_button_enabled", false)) {
   root.addView(button("Отправить всю историю") { sendMeasurementHistory("ecg") })
  }
  val history = try { JSONArray(prefs.getString("ecg_history", "[]")) } catch (_: Exception) { JSONArray() }
  if (history.length() == 0) root.addView(label("Записей пока нет", 12f))
  for (i in 0 until history.length()) {
   val item = history.optJSONObject(i) ?: continue
   val date = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(item.optLong("timestamp")))
   val file = item.optString("file")
   val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
   row.addView(button("$date · ${item.optLong("durationMs") / 1000} с · ${item.optInt("samples")} отсчётов") {
    showEcgRecording(file, date)
   }, LinearLayout.LayoutParams(0, dp(menuButtonHeightDp()), 1f))
   if (prefs.getBoolean("history_delete_button_enabled", false)) row.addView(button("✕") { confirmDeleteMeasurement("ecg", item.optLong("timestamp")) }, LinearLayout.LayoutParams(dp(34), dp(menuButtonHeightDp())))
   root.addView(row)
  }
  root.addView(label("Исходные отсчёты хранятся в CSV на часах.", 11f))
  showPage(root)
 }
 private fun exportEcgCsv(file: String, status: TextView) {
  if (!Regex("^ecg_[0-9]+\\.csv$").matches(file)) {
   status.text = "Недопустимое имя записи"
   return
  }
  var uri: Uri? = null
  try {
   val values = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, file)
    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WatchBridge")
    put(MediaStore.MediaColumns.IS_PENDING, 1)
   }
   uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    ?: throw IllegalStateException("Не удалось создать файл в Downloads")
   val destination = uri
   contentResolver.openOutputStream(destination)?.use { output ->
    openFileInput(file).use { input -> input.copyTo(output) }
   } ?: throw IllegalStateException("Не удалось открыть файл для записи")
   contentResolver.update(destination, ContentValues().apply {
    put(MediaStore.MediaColumns.IS_PENDING, 0)
   }, null, null)
   status.text = "Экспортировано: Download/WatchBridge/$file"
  } catch (e: Exception) {
   uri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
   status.text = "Ошибка экспорта: ${e.message ?: e.javaClass.simpleName}"
  }
 }
 private fun showEcgRecording(file: String, date: String) {
  val values = try {
   openFileInput(file).bufferedReader().useLines { lines ->
    lines.drop(1).mapNotNull { it.split(',').getOrNull(1)?.toFloatOrNull() }.take(30000).toList()
   }
  } catch (e: Exception) {
   screen = "ecg_details"
   val error = page()
   error.addView(label("Не удалось открыть ЭКГ: ${e.message}", 12f))
   error.addView(button("Назад") { showEcgHistory() })
   showPage(error)
   return
  }
  screen = "ecg_details"
  val root = page()
  root.addView(label(date, 15f))
  root.addView(label("ЭКГ · запись ${values.size} отсчётов", 12f))
  val waveform = EcgWaveformView(this)
  root.addView(waveform, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(145)))
  val position = label("", 12f)
  root.addView(position)
  var startSample = 0
  var filtered = true
  fun redraw() {
   waveform.setFilterEnabled(filtered)
   waveform.setRecording(values.drop(startSample).take(1500))
   position.text = "${startSample / 500}–${minOf(values.size, startSample + 1500) / 500} с · " +
    if (filtered) "Фильтр включён" else "Без фильтра"
  }
  val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  nav.addView(button("◀ 3 с") { startSample = (startSample - 1500).coerceAtLeast(0); redraw() },
   LinearLayout.LayoutParams(0, dp(menuButtonHeightDp()), 1f))
  nav.addView(button("3 с ▶") {
   startSample = (startSample + 1500).coerceAtMost(((values.size - 1) / 1500 * 1500).coerceAtLeast(0))
   redraw()
  }, LinearLayout.LayoutParams(0, dp(menuButtonHeightDp()), 1f))
  root.addView(nav)
  root.addView(button("Фильтр: вкл/выкл") { filtered = !filtered; redraw() })
  val exportStatus = label("", 11f)
  root.addView(button("Экспорт CSV в Downloads") { exportEcgCsv(file, exportStatus) })
  root.addView(exportStatus)
  root.addView(label("Фильтр только для просмотра. CSV содержит исходный сигнал.", 11f))
  root.addView(button("Назад") { showEcgHistory() })
  showPage(root)
  redraw()
 }
 private fun biaField(message: String, name: String): Float? =
  Regex("(?:^|\\s)" + Regex.escape(name) + "=(-?[0-9]+(?:\\.[0-9]+)?)")
   .find(message)?.groupValues?.get(1)?.toFloatOrNull()
 private fun formatBia(value: Float?, unit: String): String =
  if (value != null && value.isFinite()) String.format(Locale.getDefault(), "%.1f %s", value, unit) else "—"
 private fun biaSummary(message: String): String {
  val fields = listOf(
   Triple("Жир", "fatRatio", "%"),
   Triple("Жировая масса", "fatMass", "кг"),
   Triple("Вода", "bodyWater", "кг"),
   Triple("Скелетные мышцы", "muscleMass", "кг"),
   Triple("Доля мышц", "muscleRatio", "%"),
   Triple("Безжировая масса", "fatFreeMass", "кг"),
   Triple("Базовый обмен", "bmr", "ккал/сут"),
   Triple("Импеданс", "impedanceMagnitude", "Ом")
  )
  return fields.joinToString("\n") { (title, key, unit) ->
   "$title: ${formatBia(biaField(message, key), unit)}"
  }
 }
 private fun roundedBiaValue(key: String, value: Double): Number? {
  if (!value.isFinite()) return null
  val decimals = when (key) { "bmr" -> 0; "impedanceDegree" -> 2; else -> 1 }
  val factor = when (decimals) { 0 -> 1.0; 2 -> 100.0; else -> 10.0 }
  val rounded = kotlin.math.round(value * factor) / factor
  return if (decimals == 0) rounded.toLong() else rounded
 }
 private fun normalizedBiaHistory(): JSONArray {
  val original = try { JSONArray(prefs.getString("bia_history", "[]")) } catch (_: Exception) { JSONArray() }
  val normalized = JSONArray()
  val fields = listOf("fatRatio", "fatMass", "bodyWater", "muscleRatio", "muscleMass",
   "bmr", "fatFreeRatio", "fatFreeMass", "impedanceMagnitude", "impedanceDegree")
  for (i in 0 until original.length()) {
   val entry = original.optJSONObject(i) ?: continue
   val copy = JSONObject(entry.toString())
   for (key in fields) {
    if (copy.has(key) && !copy.isNull(key)) {
     val raw = copy.optDouble(key, Double.NaN)
     val rounded = roundedBiaValue(key, raw)
     if (rounded != null) copy.put(key, rounded)
    }
   }
   normalized.put(copy)
  }
  if (normalized.toString() != original.toString()) {
   prefs.edit().putString("bia_history", normalized.toString()).apply()
  }
  return normalized
 }
 private fun saveBiaResult(message: String) {
  val entry = JSONObject()
  entry.put("timestamp", System.currentTimeMillis())
  for (key in listOf("fatRatio", "fatMass", "bodyWater", "muscleRatio", "muscleMass",
                     "bmr", "fatFreeRatio", "fatFreeMass", "impedanceMagnitude", "impedanceDegree")) {
   biaField(message, key)?.let { raw ->
    roundedBiaValue(key, raw.toDouble())?.let { entry.put(key, it) }
   }
  }
  val history = normalizedBiaHistory()
  val updated = JSONArray()
  updated.put(entry)
  for (i in 0 until minOf(history.length(), 49)) updated.put(history.get(i))
  prefs.edit().putString("bia_history", updated.toString()).apply()
  if (entry.length() > 1) {
   val timestamp = entry.optLong("timestamp")
   val event = JSONObject(entry.toString()).put("kind", "bia").put("id", "bia:$timestamp")
    .put("measured_at", java.time.Instant.ofEpochMilli(timestamp).toString())
   mqtt?.publish("bia", event.toString())
   bleServer?.publishJournal(event.toString())
  }
 }
 private fun showBiaHistory() {
  screen = "bia_history"
  val root = page().apply { setPadding(dp(10), dp(2), dp(10), dp(10)) }
  root.addView(label("История BIA", 13f).apply {
   typeface = Typeface.DEFAULT_BOLD
   setPadding(dp(2), 0, dp(2), dp(2))
  })
  root.addView(button("← Назад") { showMeasurement("BIA") })
  if (prefs.getBoolean("history_send_button_enabled", false)) {
   root.addView(button("Отправить всю историю") { sendMeasurementHistory("bia") })
  }
  val history = normalizedBiaHistory()
  if (history.length() == 0) root.addView(label("Завершённых измерений пока нет", 12f))
  for (i in 0 until history.length()) {
   val entry = history.optJSONObject(i) ?: continue
   val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(entry.optLong("timestamp")))
   val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
   row.addView(button(date) {
    val details = page()
    details.addView(label(date, 16f))
    val lines = listOf(
     Triple("Жир", "fatRatio", "%"), Triple("Жировая масса", "fatMass", "кг"),
     Triple("Вода", "bodyWater", "кг"), Triple("Мышцы", "muscleMass", "кг"),
     Triple("Безжировая масса", "fatFreeMass", "кг"),
     Triple("Базовый обмен", "bmr", "ккал/сут"),
     Triple("Импеданс", "impedanceMagnitude", "Ом")
    ).joinToString("\n") { (title, key, unit) ->
     "$title: ${formatBia(if (entry.has(key)) entry.optDouble(key).toFloat() else null, unit)}"
    }
    details.addView(label(lines, 13f))
    details.addView(button("Назад") { showBiaHistory() })
    screen = "bia_details"
    showPage(details)
   }, LinearLayout.LayoutParams(0, dp(menuButtonHeightDp()), 1f))
   if (prefs.getBoolean("history_delete_button_enabled", false)) row.addView(button("✕") { confirmDeleteMeasurement("bia", entry.optLong("timestamp")) }, LinearLayout.LayoutParams(dp(34), dp(menuButtonHeightDp())))
   root.addView(row)
  }
  showPage(root)
 }
 private fun updateMeasurementUi() {
  if (screen != "measurement") return
  progressBar?.progress = measurementProgress
  progressLabel?.text = if (activeTrackerName == "BIA" || activeTrackerName == "ECG" || measurementProgress > 0) "$measurementProgress%" else "—"
  measurementStatus?.text = measurementMessage
  measurementResult?.text = measurementPreview
 }
 private var oxygenMeasurementGeneration = 0
 private fun startSpO2Measurement() {
  stopSamsungTrackerTest()
  val generation = ++oxygenMeasurementGeneration
  activeTrackerName = "SPO2"
  measurementMessage = "Измерение кислорода…"
  if (screen == "home") homeOxygenButton?.text = "O₂  …"
  spo2Probe = SamsungTrackerProbe(this) { message ->
   runOnUiThread {
    if (generation != oxygenMeasurementGeneration || activeTrackerName != "SPO2") return@runOnUiThread
    android.util.Log.i("WatchBridgeOxygen", message)
    val result = publishSpO2FromProbe(message)
    if (result != null) {
     spo2Result = result
     val oxygen = Regex("SpO₂: (\\d+)%").find(result)?.groupValues?.get(1)?.toIntOrNull()
     if (oxygen != null) {
      prefs.edit().putString("last_oxygen", "$oxygen%").apply()
      saveVital("oxygen", oxygen)
     }
     measurementMessage = "Измерение завершено"
     measurementPreview = result
     measurementProgress = 100
     stopOxygenBlink()
    } else if (message.startsWith("SPO2 status=2") && result == null) {
     measurementMessage = "Измерение завершено без достоверного результата"
     measurementPreview = message.take(160)
     spo2Result = measurementPreview
     stopOxygenBlink()
     if (screen == "home") homeOxygenButton?.text = "O₂  Нет данных"
    } else if (message.contains("error", true) || message.contains("failed", true) ||
               message.contains("timeout", true) || message.contains("status=3") ||
               message.contains("status=-6") || message.contains("tracker error code", true) ||
               message.contains("bindService returned false", true)) {
     measurementMessage = "Ошибка измерения O₂"
     measurementPreview = message.take(160)
     spo2Result = measurementPreview
     stopOxygenBlink()
     if (screen == "home") homeOxygenButton?.text = "O₂  Ошибка"
    } else if (screen == "home" && message.startsWith("SPO2 status=")) {
     // Show a neutral measurement indicator; raw status remains in Logcat.
     homeOxygenButton?.text = "O₂  …"
    }
    updateMeasurementUi()
   }
  }
  spo2Probe?.start()
 }
 private fun showSystemDiagnostics() {
  screen = "system_diagnostics"
  val root = page()
  root.addView(label("Система", 18f))
  output = label("", 11f).apply { gravity = Gravity.START }
  root.addView(button("Разрешения") { requestMissingPermissions() })
  root.addView(button("Samsung provider") { checkProvider() })
  root.addView(button("Samsung services") { checkSamsungServices() })
  root.addView(button("BLE: запустить") { startBle() })
  root.addView(button("BLE: остановить") { bleServer?.stop(); bleServer = null; bleInfo = "BLE stopped"; refresh() })
  root.addView(button("Health Connect: 7 дней") { checkHealthConnect() })
  root.addView(button("Разрешения Health Connect") { requestHealthPermissions() })
  root.addView(button("Датчики Android") { startSensors() })
  root.addView(button("Назад") { showDiagnostics() })
  root.addView(output)
  showPage(root)
  refresh()
 }
 private fun biaMessage(code: Int): String = when(code) {
  0 -> "Измерение… удерживай пальцы"
  2 -> "Проверка датчика (NV_NG)"
  4 -> "Часы не касаются запястья"
  7, 8, 9 -> "Коснись обеих боковых кнопок"
  10 -> "Поправь ремешок: слабый контакт"
  11 -> "Слишком сухие пальцы"
  17 -> "Нестабильный контакт"
  else -> "Ошибка BIA: $code"
 }

 private var heart: Float? = null
 private var steps: Float? = null
 private var sensorInfo = "Waiting for permissions"
 private var providerInfo = "Not checked"
 private var samsungServicesInfo = "Not checked"
 private val samsungConnections = mutableListOf<ServiceConnection>()
 private var healthConnectInfo = "Not checked"
 private var spo2Probe: SamsungTrackerProbe? = null
 private var spo2Result = "—"
 private var samsungTrackerTestResult = "Не запускался"
 private var activeTrackerName: String? = null
 private var trackerTestGeneration = 0
 private var trackerSampleCount = 0
 private val trackerSamplePreviews = mutableListOf<String>()
 private var trackerLastUiUpdate = 0L
 private var mqtt: WatchMqtt? = null
 private var mqttInfo = "MQTT выключен"
 private var bleServer: WatchBleServer? = null
 private var bleInfo = "BLE not started"
 private val permissions = arrayOf(
  Manifest.permission.ACTIVITY_RECOGNITION,
  "android.permission.health.READ_HEART_RATE"
 )
 private var healthPermissionRequestPending = false
 private val healthPermissions = arrayOf(
  "android.permission.health.READ_HEART_RATE",
  "android.permission.health.READ_SLEEP",
  "android.permission.health.READ_STEPS",
  "android.permission.health.READ_OXYGEN_SATURATION"
 )

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val prefs = getSharedPreferences("watch_bridge", MODE_PRIVATE)
  if (prefs.getString("device_id", null) == null) {
   prefs.edit().putString("device_id", "wearos_" + UUID.randomUUID().toString().replace("-", "")).apply()
  }
  manager = getSystemService(SENSOR_SERVICE) as SensorManager
  mqtt = WatchMqtt(this) { msg -> runOnUiThread { mqttInfo = msg; refresh() } }
  if (prefs.getBoolean("ble_enabled", false)) startBle()
  if (prefs.getBoolean("background_sync_enabled", false)) updateBackgroundService()
  showHome()
  requestMissingPermissions()
  refresh()
 }

 private fun updateBackgroundService() {
  val intent = Intent(this, WatchSyncService::class.java)
  if (prefs.getBoolean("background_sync_enabled", false)) {
   intent.action = WatchSyncService.ACTION_START
   try { androidxStartForegroundService(intent) } catch (e: Exception) {
    android.util.Log.w("WatchBridgeSync", "Cannot start foreground service", e)
    android.widget.Toast.makeText(this, "Не удалось запустить фоновый сервис: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
   }
  } else {
   intent.action = WatchSyncService.ACTION_STOP
   startService(intent)
  }
  if (::manager.isInitialized) startSensors()
 }
 private fun androidxStartForegroundService(intent: Intent) {
  if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
 }
 private fun startBle() {
  if (Build.VERSION.SDK_INT >= 31) {
   val needed = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
   if (needed.isNotEmpty()) {
    bleInfo = "Grant Bluetooth permissions and tap BLE GATT again"
    requestPermissions(needed.toTypedArray(), 102)
    refresh()
    return
   }
  }
  if (bleServer == null) bleServer = WatchBleServer(this) { msg ->
   runOnUiThread { bleInfo = msg; refresh() }
  }
  bleServer?.start()
 }
 private fun stopSamsungTrackerTest() {
  trackerTestGeneration++
  val previous = activeTrackerName
  activeTrackerName = null
  spo2Probe?.stop()
  spo2Probe = null
  if (previous != null) {
   samsungTrackerTestResult = "$previous: остановлено, событий $trackerSampleCount" +
    if (trackerSamplePreviews.isNotEmpty()) "\nПервые образцы:\n" + trackerSamplePreviews.joinToString("\n") else ""
  }
  refresh()
 }
 private fun parseBirthDate(value: String?): LocalDate? = try {
  value?.let { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }
 } catch (_: DateTimeParseException) { null }
 private fun bodyProfile(): SamsungTrackerProbe.BodyProfile? {
  val h = prefs.getString("body_height", null)?.toFloatOrNull() ?: return null
  val w = prefs.getString("body_weight", null)?.toFloatOrNull() ?: return null
  val birthday = parseBirthDate(prefs.getString("body_birth_date", null)) ?: return null
  val a = Period.between(birthday, LocalDate.now()).years
  if (h !in 100f..250f || w !in 20f..300f || a !in 18..120) return null
  return SamsungTrackerProbe.BodyProfile(h, w, prefs.getInt("body_gender", 1), a)
 }
 private fun startSamsungTrackerTest(tracker: String) {
  stopSamsungTrackerTest()
  activeTrackerName = tracker
  trackerSampleCount = 0
  trackerSamplePreviews.clear()
  trackerLastUiUpdate = 0L
  if (tracker != "ECG") measurementProgress = 0
  biaSavedForRun = false
  measurementMessage = "Ожидание данных датчика…"
  measurementPreview = ""
  updateMeasurementUi()
  val generation = trackerTestGeneration
  val probe = SamsungTrackerProbe(this, tracker, if (tracker == "BIA") bodyProfile() else null,
   if (tracker == "ECG") ({ value: Float, contact: Int, time: Long -> receiveEcgSample(value, contact, time) }) else null
  ) { message ->
   if (generation != trackerTestGeneration || activeTrackerName != tracker) return@SamsungTrackerProbe
   if (tracker == "BIA" && message.startsWith("BIA samples=")) {
    val status = Regex("status=(-?\\d+)").find(message)?.groupValues?.get(1)?.toIntOrNull()
    val progress = Regex("progress=([0-9.]+)").find(message)?.groupValues?.get(1)?.toFloatOrNull()
    runOnUiThread {
     if (generation != trackerTestGeneration) return@runOnUiThread
     if (status != null && !biaSavedForRun) measurementMessage = biaMessage(status)
     if (status == 0 && progress != null && !biaSavedForRun) {
      measurementProgress = (progress * 100f).toInt().coerceIn(0, 100)
      if (measurementProgress >= 100) {
       measurementMessage = "Измерение завершено"
       measurementPreview = biaSummary(message)
       if (!biaSavedForRun) {
        biaSavedForRun = true
        saveBiaResult(message)
       }
      } else if (!biaSavedForRun) {
       measurementPreview = ""
      }
     }
     updateMeasurementUi()
    }
   } else if (message.startsWith("$tracker samples=")) {
    trackerSampleCount++
    val now = android.os.SystemClock.elapsedRealtime()
    if (now - trackerLastUiUpdate >= 1000L) {
     trackerLastUiUpdate = now
     runOnUiThread {
      if (generation == trackerTestGeneration) {
       if (tracker != "ECG") {
        measurementMessage = "Получение данных…"
        measurementPreview = "Событий: $trackerSampleCount"
       }
       updateMeasurementUi()
      }
     }
    }
   } else if (message.contains("failed", true) || message.contains("error", true) ||
              message.contains("timeout", true) || message.contains("bindService returned false")) {
    runOnUiThread {
     if (generation == trackerTestGeneration && !(tracker == "BIA" && biaSavedForRun)) {
      measurementMessage = "Ошибка: " + message.take(100)
      updateMeasurementUi()
     }
    }
   }
  }
  spo2Probe = probe
  probe.start()
 }
 private fun publishSpO2FromProbe(message: String): String? {
  val match = Regex("SPO2 status=(-?\\d+) value=(\\d+) pulse=(\\d+) accuracy=(-?\\d+) timestamp=(\\d+)", RegexOption.IGNORE_CASE).find(message) ?: return null
  val (status, oxygen, pulse, _, timestamp) = match.destructured
  val spo2 = oxygen.toIntOrNull() ?: return null
  // Only status 2 with a real, non-zero oxygen reading is a valid result.
  if (status != "2" || spo2 !in 1..100) return null
  val bpm = pulse.toIntOrNull() ?: 0
  bleServer?.publish(spo2, bpm, timestamp.toLong())
  return "SpO₂: $spo2%  |  Пульс: $bpm"
 }
 private fun requestMissingPermissions() {
  val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
  if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100)
  else startSensors()
 }

 override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
  super.onRequestPermissionsResult(requestCode, permissions, grantResults)
  if (requestCode == 100) { startSensors(); if (prefs.getBoolean("background_sync_enabled", false)) updateBackgroundService() }
  if (requestCode == 101) {
   healthPermissionRequestPending = false
   val denied = healthPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
   healthConnectInfo = if (denied.isEmpty()) "Permissions granted. Tap Health Connect again to read." else
    "Permission denied: " + denied.joinToString(", ") { it.substringAfterLast('.') }
   refresh()
  }
 }

 private fun startSensors() {
  manager.unregisterListener(this)
  if (prefs.getBoolean("background_sync_enabled", false)) {
   sensorInfo = "Датчики обслуживает фоновый сервис"
   return
  }
  val details = mutableListOf<String>()
  val sensors = listOf(Sensor.TYPE_HEART_RATE, Sensor.TYPE_STEP_COUNTER)
  for (type in sensors) {
   val permission = if (type == Sensor.TYPE_HEART_RATE) "android.permission.health.READ_HEART_RATE"
     else Manifest.permission.ACTIVITY_RECOGNITION
   if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
    details.add("$type: permission denied")
    continue
   }
   val sensor = manager.getDefaultSensor(type)
   if (sensor == null) {
    details.add("$type: not found")
   } else {
    val registered = try { manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
      catch (e: SecurityException) { false }
    details.add("${sensor.name}: ${if (registered) "listening" else "registration failed"}")
   }
  }
  sensorInfo = details.joinToString("\n")
  refresh()
 }

 private fun requestHealthPermissions() {
  if (Build.VERSION.SDK_INT < 34 || healthPermissionRequestPending) return
  val missing = healthPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
  if (missing.isEmpty()) {
   healthConnectInfo = "All Health Connect permissions granted."
   refresh()
   return
  }
  healthPermissionRequestPending = true
  healthConnectInfo = "Requesting: " + missing.joinToString(", ") { it.substringAfterLast('.') }
  refresh()
  try { requestPermissions(missing.toTypedArray(), 101) }
  catch (e: Exception) {
   healthPermissionRequestPending = false
   healthConnectInfo = "Permission request failed: ${e.javaClass.simpleName}: ${e.message}"
   refresh()
  }
 }

 private fun checkHealthConnect() {
  if (Build.VERSION.SDK_INT < 34) {
   healthConnectInfo = "Health Connect requires API 34+"
   refresh()
   return
  }
  val missing = healthPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
  if (missing.isNotEmpty()) {
   healthConnectInfo = "Missing permissions: " + missing.joinToString(", ") { it.substringAfterLast('.') } +
    "\nUse the separate permission button to request access."
   refresh()
   return
  }
  val hcByClass = try { getSystemService(HealthConnectManager::class.java) } catch (e: Exception) {
   Log.e("WatchBridgeHC", "HealthConnectManager class lookup failed", e)
   null
  }
  val hcByName = try { getSystemService(Context.HEALTHCONNECT_SERVICE) } catch (e: Exception) {
   Log.e("WatchBridgeHC", "Health Connect name lookup failed", e)
   null
  }
  Log.i("WatchBridgeHC", "API=${Build.VERSION.SDK_INT}, class=${hcByClass?.javaClass?.name ?: "null"}, name=${hcByName?.javaClass?.name ?: "null"}")
  val hc = hcByClass ?: (hcByName as? HealthConnectManager)
  if (hc == null) {
   healthConnectInfo = "Health Connect service unavailable\n" +
    "API: ${Build.VERSION.SDK_INT}\\n" +
    "By class: ${hcByClass?.javaClass?.name ?: "null"}\\n" +
    "By name: ${hcByName?.javaClass?.name ?: "null"}"
   refresh()
   return
  }
  healthConnectInfo = "Reading last 7 days..."
  refresh()
  val from = Instant.now().minus(7, ChronoUnit.DAYS)
  val to = Instant.now()
  readHealthRecords(hc, SleepSessionRecord::class.java, from, to, "Sleep") { records ->
   records.take(3).joinToString(" | ") { r ->
    "${r.startTime}–${r.endTime} stages=${r.stages.size} origin=${r.metadata.dataOrigin.packageName}"
   }
  }
  readHealthRecords(hc, HeartRateRecord::class.java, from, to, "Heart rate") { records ->
   records.take(3).joinToString(" | ") { r ->
    "samples=${r.samples.size} origin=${r.metadata.dataOrigin.packageName}"
   }
  }
  readHealthRecords(hc, StepsRecord::class.java, from, to, "Steps") { records ->
   records.take(3).joinToString(" | ") { r ->
    "count=${r.count} origin=${r.metadata.dataOrigin.packageName}"
   }
  }
  readHealthRecords(hc, OxygenSaturationRecord::class.java, from, to, "SpO2") { records ->
   records.take(3).joinToString(" | ") { r ->
    "value=${r.percentage.value} origin=${r.metadata.dataOrigin.packageName}"
   }
  }
 }

 private fun <T : Record> readHealthRecords(
  hc: HealthConnectManager, clazz: Class<T>, from: Instant, to: Instant,
  title: String, describe: (List<T>) -> String
 ) {
  try {
   val request = ReadRecordsRequestUsingFilters.Builder(clazz)
    .setTimeRangeFilter(TimeInstantRangeFilter.Builder().setStartTime(from).setEndTime(to).build())
    .setPageSize(100)
    .build()
   hc.readRecords(request, mainExecutor, object : OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException> {
    override fun onResult(result: ReadRecordsResponse<T>) {
     try {
      val rows = result.records
      val preview = if (rows.isEmpty()) "No records" else describe(rows)
      healthConnectInfo += "\n$title: ${rows.size} records; $preview" +
       if (result.nextPageToken != -1L) " (more pages)" else ""
     } catch (e: Exception) {
      healthConnectInfo += "\n$title: result error: ${e.javaClass.simpleName}: ${e.message}"
     }
     refresh()
    }
    override fun onError(error: HealthConnectException) {
     healthConnectInfo += "\n$title: ${error.javaClass.simpleName}: ${error.message}"
     refresh()
    }
   })
  } catch (e: Exception) {
   healthConnectInfo += "\n$title: ${e.javaClass.simpleName}: ${e.message}"
   refresh()
  }
 }

 private fun checkSamsungServices() {
  samsungServicesInfo = "Binding to Samsung services..."
  refresh()
  val targets = listOf(
   "HSP" to ".data.hsp.HealthPlatformService",
   "Tracker" to ".tracking.requesthandler.WearTrackerService"
  )
  for ((label, className) in targets) {
   val fullClass = "com.samsung.android.service.health" + className
   val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
     val descriptor = try { binder.interfaceDescriptor } catch (e: Exception) {
      "error: ${e.javaClass.simpleName}: ${e.message}"
     }
     val msg = "$label connected: $descriptor"
     Log.i("WatchBridgeSamsung", msg)
     samsungServicesInfo += "\n" + msg
     refresh()
     if (label == "Tracker" && descriptor == "com.samsung.android.service.health.tracking.IClientEventListener") {
      Thread {
       val request = Parcel.obtain()
       val response = Parcel.obtain()
       val result = try {
        request.writeInterfaceToken(descriptor)
        val handled = binder.transact(5, request, response, 0)
        if (!handled) "transaction 5 not handled"
        else {
         response.readException()
         val types = response.createStringArrayList()
         "Supported trackers: " + (types?.joinToString(", ") ?: "null")
        }
       } catch (e: Exception) {
        "Tracker query failed: ${e.javaClass.simpleName}: ${e.message}"
       } finally {
        response.recycle()
        request.recycle()
       }
       Log.i("WatchBridgeSamsung", result)
       runOnUiThread {
        samsungServicesInfo += "\n" + result
        refresh()
       }
      }.start()
     }
    }
    override fun onServiceDisconnected(name: ComponentName) {
     Log.i("WatchBridgeSamsung", "$label disconnected")
     samsungServicesInfo += "\n$label disconnected"
     refresh()
    }
    override fun onNullBinding(name: ComponentName) {
     Log.i("WatchBridgeSamsung", "$label null binding")
     samsungServicesInfo += "\n$label null binding"
     refresh()
    }
    override fun onBindingDied(name: ComponentName) {
     Log.i("WatchBridgeSamsung", "$label binding died")
     samsungServicesInfo += "\n$label binding died"
     refresh()
    }
   }
   try {
    val intent = Intent().setComponent(ComponentName("com.samsung.android.service.health", fullClass))
    val bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
    Log.i("WatchBridgeSamsung", "$label bindService=$bound")
    samsungServicesInfo += "\n$label bindService=$bound"
    if (bound) samsungConnections.add(connection)
   } catch (e: Exception) {
    val msg = "$label ${e.javaClass.simpleName}: ${e.message}"
    Log.e("WatchBridgeSamsung", msg, e)
    samsungServicesInfo += "\n" + msg
   }
  }
  refresh()
 }

 private fun checkProvider() {
  val authority = "com.samsung.android.wear.shealth.healthdataprovider"
  val info = packageManager.resolveContentProvider(authority, 0)
  if (info == null) {
   providerInfo = "Provider not visible / not found"
  } else {
   val permission = "com.samsung.android.wear.shealth.healthdataprovider.permission.READ"
   val granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
   val uri = Uri.parse("content://$authority")
   val types = listOf("heart_rate", "steps", "sleep", "spo2")
   val results = types.map { type ->
    try {
     val response = contentResolver.call(uri, type, null, Bundle())
     val summary = if (response == null) "null bundle" else {
      response.keySet().sorted().joinToString(", ") { key ->
       val value = response.get(key)
       "$key=${value?.toString()?.take(120) ?: "null"}"
      }.ifEmpty { "empty bundle" }
     }
     "$type: $summary"
    } catch (e: Exception) {
     "$type: ${e.javaClass.simpleName}: ${e.message?.take(160)}"
    }
   }
   providerInfo = "exported=${info.exported}, readPermission=${info.readPermission}, granted=$granted\n" +
    results.joinToString("\n")
  }
  refresh()
 }

 private fun refresh() {
  if (screen == "home") { heartValue?.text = heart?.toInt()?.toString() ?: "—"; stepsValue?.text = "♟  " + (todaySteps()?.toString() ?: "—") }
  if (screen != "system_diagnostics" || !::output.isInitialized) return
  output.text = "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
     "Heart rate: ${heart?.toString() ?: "waiting"} bpm\n" +
     "Step counter: ${steps?.toInt()?.toString() ?: "waiting"} (since reboot)\n\n" +
     "Sensors:\n$sensorInfo\n\nSamsung provider:\n$providerInfo\n\n" +
     "Health Connect:\n$healthConnectInfo\n\nBLE:\n$bleInfo\n\nSpO₂:\n$spo2Result\n\nSamsung tracker test:\n$samsungTrackerTestResult\n\nSamsung services:\n$samsungServicesInfo\n\nNo data sent to MQTT."
 }

 override fun onSensorChanged(event: SensorEvent) {
  if (event.values.isEmpty()) return
  when (event.sensor.type) {
   Sensor.TYPE_HEART_RATE -> {
    val bpm = event.values[0]
    if (bpm.isFinite() && bpm in 25f..240f) {
     heart = bpm
     if ((pulseAnimating && screen == "home") || prefs.getBoolean("auto_heart_collection", false)) {
      prefs.edit().putString("last_heart", "${bpm.toInt()} уд/мин").apply()
      saveVital("heart", bpm.toInt())
      if (screen == "home") renderHeartButton()
     }
    }
   }
   Sensor.TYPE_STEP_COUNTER -> {
    steps = event.values[0]
    recordStepCounter(event.values[0])
   }
  }
  refresh()
 }

 override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
 override fun onDestroy() {
  spo2Probe?.stop()
  bleServer?.stop()
  mqtt?.close()
  for (connection in samsungConnections) try { unbindService(connection) } catch (_: Exception) {}
  samsungConnections.clear()
  super.onDestroy()
 }
 override fun onPause() {
  super.onPause()
  manager.unregisterListener(this)
 }
 override fun onResume() {
  super.onResume()
  if (::manager.isInitialized) startSensors()
 }
}
