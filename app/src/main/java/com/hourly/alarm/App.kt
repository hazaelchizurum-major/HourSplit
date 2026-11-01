package com.hourly.alarm

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*

const val MIN = 60000L
val BLUE = Color.rgb(0, 140, 255)

fun prefs(c: Context) = c.getSharedPreferences("p", 0)
fun Context.tv(s: String, sz: Float) = TextView(this).apply { text = s; textSize = sz; setTextColor(BLUE); gravity = Gravity.CENTER }
fun Context.bt(s: String, f: () -> Unit) = Button(this).apply { text = s; setTextColor(Color.BLACK); setBackgroundColor(BLUE); setOnClickListener { f() } }
fun Context.col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK); setPadding(48, 48, 48, 48) }
fun LinearLayout.add(v: View) = addView(v, LinearLayout.LayoutParams(-1, -2))

fun pend(c: Context, i: Intent = Intent(c, AlarmReceiver::class.java)) =
    PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

// Schedules the next alarm after time "ref", always computed from the start time (no drift).
fun schedule(c: Context, ref: Long) {
    val start = prefs(c).getLong("start", 0)
    val pos = (ref - start) % (60 * MIN)
    val type = if (pos < 45 * MIN) 0 else 1
    val at = ref - pos + (if (type == 0) 45 else 60) * MIN
    val show = PendingIntent.getActivity(c, 2, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    c.getSystemService(AlarmManager::class.java).setAlarmClock(
        AlarmManager.AlarmClockInfo(at, show),
        pend(c, Intent(c, AlarmReceiver::class.java).putExtra("type", type).putExtra("at", at)))
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (!prefs(c).getBoolean("on", false)) return
        schedule(c, i.getLongExtra("at", System.currentTimeMillis()) + 1000)
        c.startForegroundService(Intent(c, AlarmService::class.java).putExtra("type", i.getIntExtra("type", 0)))
    }
}

class AlarmService : Service() {
    var mp: MediaPlayer? = null
    var vib: Vibrator? = null
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        halt()
        val t = i?.getIntExtra("type", 0) ?: 0
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("a", "Alarm", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val full = PendingIntent.getActivity(this, 3,
            Intent(this, AlarmActivity::class.java).putExtra("type", t).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "a").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (t == 0) "Main Time" else "Part Time").setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(full, true).setContentIntent(full).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        val def = RingtoneManager.getDefaultUri(if (t == 0) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_RINGTONE)
        val chosen = prefs(this).getString("snd$t", null)?.let { Uri.parse(it) }
        for (u in listOfNotNull(chosen, def)) {
            try {
                mp = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(this@AlarmService, u); isLooping = true; prepare(); start()
                }
                break
            } catch (e: Exception) { mp?.release(); mp = null }
        }
        vib = getSystemService(Vibrator::class.java).also { it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0)) }
        return START_NOT_STICKY
    }
    fun halt() { mp?.release(); mp = null; vib?.cancel() }
    override fun onDestroy() { halt() }
}

class AlarmActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val t = intent.getIntExtra("type", 0)
        val l = col()
        l.add(tv(if (t == 0) "Main Time" else "Part Time", 22f))
        l.add(tv(prefs(this).getString("s$t", "")?.ifEmpty { "-" } ?: "-", 44f))
        l.add(bt("STOP") { stopService(Intent(this, AlarmService::class.java)); finish() })
        setContentView(l)
    }
}

class MainActivity : Activity() {
    val h = Handler(Looper.getMainLooper())
    lateinit var lab: TextView; lateinit var clk: TextView; lateinit var btn: Button
    lateinit var ea: EditText; lateinit var eb: EditText

    fun ed(k: String, hn: String) = EditText(this).apply {
        hint = hn; setHintTextColor(Color.rgb(0, 80, 150)); setTextColor(BLUE)
        setText(prefs(this@MainActivity).getString(k, ""))
    }
    fun save() { prefs(this).edit().putString("s0", ea.text.toString()).putString("s1", eb.text.toString()).apply() }
    fun pick(code: Int) = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*")
        .addCategory(Intent.CATEGORY_OPENABLE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), code)

    fun toggle() {
        val p = prefs(this)
        if (p.getBoolean("on", false)) {
            p.edit().putBoolean("on", false).apply()
            getSystemService(AlarmManager::class.java).cancel(pend(this))
        } else {
            save(); val now = System.currentTimeMillis()
            p.edit().putBoolean("on", true).putLong("start", now).apply(); schedule(this, now)
        }
    }

    fun perms() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent())
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = col()
        lab = tv("", 22f); clk = tv("00:00", 72f)
        ea = ed("s0", "Main Time subject"); eb = ed("s1", "Part Time subject")
        btn = bt("ON") { toggle() }
        l.add(lab); l.add(clk); l.add(ea); l.add(eb); l.add(btn)
        l.add(bt("Main Time sound") { pick(10) }); l.add(bt("Part Time sound") { pick(11) })
        setContentView(l)
        perms()
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        val u = d?.data ?: return
        if (c != RESULT_OK || (r != 10 && r != 11)) return
        contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs(this).edit().putString("snd${r - 10}", u.toString()).apply()
    }

    val tick = object : Runnable {
        override fun run() {
            val p = prefs(this@MainActivity); val on = p.getBoolean("on", false)
            if (on) {
                val pos = (System.currentTimeMillis() - p.getLong("start", 0)) % (60 * MIN)
                val a = pos < 45 * MIN
                lab.text = if (a) "Main Time" else "Part Time"
                val s = (if (a) pos else pos - 45 * MIN) / 1000
                clk.text = "%02d:%02d".format(s / 60, s % 60)
            } else { lab.text = "OFF"; clk.text = "00:00" }
            btn.text = if (on) "OFF" else "ON"
            h.postDelayed(this, 250)
        }
    }
    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick); save() }
}
