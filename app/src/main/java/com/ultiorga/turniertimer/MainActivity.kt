package com.ultiorga.turniertimer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var floatVolLautstaerke: Float = 0.8f
    private var floatVolJingle: Float = 0.8f
    private val stringPrefName = "TurnierTimerPrefs"

    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stringSpielNr = intent?.getStringExtra(TimerService.EXTRA_SPIEL_NR) ?: return
            val stringNaechstes = intent.getStringExtra(TimerService.EXTRA_NAECHSTES) ?: ""
            val stringLetzteMinJingleInfo = intent.getStringExtra(TimerService.EXTRA_LETZTE_MIN_JINGLE) ?: ""
            val stringSchlussJingleInfo = intent.getStringExtra(TimerService.EXTRA_SCHLUSS_JINGLE) ?: ""
            runOnUiThread {
                findViewById<TextView>(R.id.tvSpielInfo).text = stringSpielNr
                findViewById<TextView>(R.id.tvNaechstesSpiel).text = stringNaechstes
                findViewById<TextView>(R.id.tvLetzteMinJingleInfo).text = stringLetzteMinJingleInfo
                findViewById<TextView>(R.id.tvSchlussJingleInfo).text = stringSchlussJingleInfo
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        supportActionBar?.hide()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        berechtigungenAnfragen()
        setupLautstaerkeRegler()
        setupButtons()
        registriereReceiver()
        ladeVolEinstellungen()
    }

    override fun onResume() {
        super.onResume()
        ladeVolEinstellungen()
        val timerLaeuft = getSharedPreferences("TimerServiceState", Context.MODE_PRIVATE)
            .getBoolean("S_LAEUFT", false)
        aktualisiereEinstellungenButton(timerLaeuft)
        if (timerLaeuft) {
            val prefs = getSharedPreferences(stringPrefName, Context.MODE_PRIVATE)
            val intHour = prefs.getInt("PREF_HOUR_START", -1)
            val intMin = prefs.getInt("PREF_MIN_START", -1)
            if (intHour != -1) {
                findViewById<TextView>(R.id.tvStatus).text =
                    "✅ Timer läuft! Start: %02d:%02d Uhr".format(intHour, intMin)
            }
        }
        startService(Intent(this, TimerService::class.java).apply {
            action = TimerService.ACTION_STATUS_ANFRAGEN
        })
    }

    private fun aktualisiereEinstellungenButton(timerLaeuft: Boolean) {
        val btn = findViewById<Button>(R.id.btnEinstellungen)
        btn.isEnabled = !timerLaeuft
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (timerLaeuft) android.graphics.Color.parseColor("#AAAAAA")
            else android.graphics.Color.parseColor("#607D8B")
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(timerReceiver)
    }

    private fun ladeVolEinstellungen() {
        val prefs = getSharedPreferences(stringPrefName, Context.MODE_PRIVATE)
        val audioManagerSystem = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val intVolMax = audioManagerSystem.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val floatVolGespeichert = prefs.getFloat("PREF_VOL", 0.8f)
        floatVolLautstaerke = floatVolGespeichert
        val intVolAkt = (floatVolGespeichert * intVolMax).toInt()
        audioManagerSystem.setStreamVolume(AudioManager.STREAM_MUSIC, intVolAkt, 0)
        findViewById<android.widget.SeekBar>(R.id.seekBarLautstaerke).progress = intVolAkt
        findViewById<TextView>(R.id.textViewLautstaerke).text = "Medien Lautstärke: $intVolAkt / $intVolMax"

        val floatVolJingleGespeichert = prefs.getFloat("PREF_VOL_JINGLE", 0.8f)
        floatVolJingle = floatVolJingleGespeichert
        val intVolJingleAkt = (floatVolJingleGespeichert * intVolMax).toInt()
        findViewById<android.widget.SeekBar>(R.id.seekBarJingleLautstaerke).progress = intVolJingleAkt
        findViewById<TextView>(R.id.textViewJingleLautstaerke).text =
            "Jingle Lautstärke: $intVolJingleAkt / $intVolMax"
    }

    private fun setupLautstaerkeRegler() {
        val audioManagerSystem = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val intVolMax = audioManagerSystem.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val seekBarMedia = findViewById<android.widget.SeekBar>(R.id.seekBarLautstaerke)
        val tvLautstaerke = findViewById<TextView>(R.id.textViewLautstaerke)
        seekBarMedia.max = intVolMax
        seekBarMedia.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, intProgress: Int, boolFromUser: Boolean) {
                audioManagerSystem.setStreamVolume(AudioManager.STREAM_MUSIC, intProgress, 0)
                tvLautstaerke.text = "Medien Lautstärke: $intProgress / $intVolMax"
                floatVolLautstaerke = intProgress / intVolMax.toFloat()
                startService(Intent(this@MainActivity, TimerService::class.java).apply {
                    action = TimerService.ACTION_LAUTSTAERKE
                    putExtra(TimerService.EXTRA_LAUTSTAERKE_WERT, floatVolLautstaerke)
                })
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                getSharedPreferences(stringPrefName, Context.MODE_PRIVATE).edit()
                    .putFloat("PREF_VOL", floatVolLautstaerke).apply()
            }
        })

        val seekBarJingle = findViewById<android.widget.SeekBar>(R.id.seekBarJingleLautstaerke)
        val tvJingleLautstaerke = findViewById<TextView>(R.id.textViewJingleLautstaerke)
        seekBarJingle.max = intVolMax
        seekBarJingle.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, intProgress: Int, boolFromUser: Boolean) {
                tvJingleLautstaerke.text = "Jingle Lautstärke: $intProgress / $intVolMax"
                floatVolJingle = intProgress / intVolMax.toFloat()
                startService(Intent(this@MainActivity, TimerService::class.java).apply {
                    action = TimerService.ACTION_LAUTSTAERKE_JINGLE
                    putExtra(TimerService.EXTRA_LAUTSTAERKE_JINGLE_WERT, floatVolJingle)
                })
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                getSharedPreferences(stringPrefName, Context.MODE_PRIVATE).edit()
                    .putFloat("PREF_VOL_JINGLE", floatVolJingle).apply()
            }
        })
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnEinstellungen).setOnClickListener {
            startActivity(Intent(this, EinstellungenActivity::class.java))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val prefs = getSharedPreferences(stringPrefName, Context.MODE_PRIVATE)
            val intHourStart = prefs.getInt("PREF_HOUR_START", -1)
            val intMinStart = prefs.getInt("PREF_MIN_START", -1)
            val intMinZeitSlot = prefs.getString("PREF_ZEITSLOT", "")?.toIntOrNull()
            val intMinLetzteMin = prefs.getString("PREF_END_MIN", "")?.toIntOrNull()
            val intMinSchluss = prefs.getString("PREF_SCHLUSS_MIN", "")?.toIntOrNull()
            val stringUriStart = prefs.getString("PREF_URI_START_JINGLE", "")
            val stringUriEnd = prefs.getString("PREF_URI_END_JINGLE", "")
            val stringUriSchluss = prefs.getString("PREF_URI_SCHLUSS_JINGLE", "")

            when {
                intHourStart == -1 ->
                    Toast.makeText(this, "Bitte Startzeit in den Einstellungen wählen!", Toast.LENGTH_SHORT).show()
                intMinZeitSlot == null || intMinZeitSlot <= 0 ->
                    Toast.makeText(this, "Bitte gültigen Zeitslot in den Einstellungen eingeben!", Toast.LENGTH_SHORT).show()
                intMinLetzteMin == null || intMinLetzteMin <= 0 ->
                    Toast.makeText(this, "Bitte End-Minuten in den Einstellungen eingeben!", Toast.LENGTH_SHORT).show()
                intMinLetzteMin >= intMinZeitSlot ->
                    Toast.makeText(this, "End-Minuten müssen kleiner als Zeitslot sein!", Toast.LENGTH_SHORT).show()
                intMinSchluss == null || intMinSchluss <= 0 ->
                    Toast.makeText(this, "Bitte Schluss-Minuten in den Einstellungen eingeben!", Toast.LENGTH_SHORT).show()
                intMinSchluss >= intMinZeitSlot ->
                    Toast.makeText(this, "Schluss-Minuten müssen kleiner als Zeitslot sein!", Toast.LENGTH_SHORT).show()
                intMinSchluss <= intMinLetzteMin ->
                    Toast.makeText(this, "Schluss-Minuten müssen größer als End-Minuten sein!", Toast.LENGTH_SHORT).show()
                stringUriStart.isNullOrEmpty() ->
                    Toast.makeText(this, "Bitte Start-Jingle in den Einstellungen auswählen!", Toast.LENGTH_SHORT).show()
                stringUriEnd.isNullOrEmpty() ->
                    Toast.makeText(this, "Bitte Letzte-x-Minuten-Jingle in den Einstellungen auswählen!", Toast.LENGTH_SHORT).show()
                stringUriSchluss.isNullOrEmpty() ->
                    Toast.makeText(this, "Bitte Schluss-Jingle in den Einstellungen auswählen!", Toast.LENGTH_SHORT).show()
                else -> {
                    aktualisiereEinstellungenButton(true)
                    starteService(
                        intHourStart, intMinStart,
                        intMinZeitSlot, intMinLetzteMin, intMinSchluss,
                        stringUriStart!!, stringUriEnd!!, stringUriSchluss!!
                    )
                }
            }
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, TimerService::class.java))
            aktualisiereEinstellungenButton(false)
            findViewById<TextView>(R.id.tvStatus).text = "⏹ Timer gestoppt"
            findViewById<TextView>(R.id.tvSpielInfo).text = ""
            findViewById<TextView>(R.id.tvNaechstesSpiel).text = ""
            findViewById<TextView>(R.id.tvLetzteMinJingleInfo).text = ""
            findViewById<TextView>(R.id.tvSchlussJingleInfo).text = ""
        }
    }

    private fun starteService(
        intHourStart: Int, intMinStart: Int,
        intMinZeitSlot: Int, intMinLetzteMin: Int, intMinSchluss: Int,
        stringUriStart: String, stringUriEnd: String, stringUriSchluss: String
    ) {
        startForegroundService(Intent(this, TimerService::class.java).apply {
            putExtra("START_STUNDE", intHourStart)
            putExtra("START_MINUTE", intMinStart)
            putExtra("ZEITSLOT", intMinZeitSlot)
            putExtra("LETZTE_MIN_MINUTEN", intMinLetzteMin)
            putExtra("SCHLUSS_MINUTEN", intMinSchluss)
            putExtra("START_JINGLE", stringUriStart)
            putExtra("LETZTE_MIN_JINGLE", stringUriEnd)
            putExtra("SCHLUSS_JINGLE", stringUriSchluss)
            putExtra("LAUTSTAERKE", floatVolLautstaerke)
            putExtra("LAUTSTAERKE_JINGLE", floatVolJingle)
        })
        findViewById<TextView>(R.id.tvStatus).text =
            "✅ Timer läuft! Start: %02d:%02d Uhr".format(intHourStart, intMinStart)
    }

    private fun registriereReceiver() {
        val intentFilterTimer = IntentFilter(TimerService.BROADCAST_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timerReceiver, intentFilterTimer, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(timerReceiver, intentFilterTimer)
        }
    }

    private fun berechtigungenAnfragen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }
}
