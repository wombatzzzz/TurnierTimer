package com.ultiorga.turniertimer

import android.Manifest
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    // ─── Zustand ────────────────────────────────────────────────────────────────

    /** URI der gewählten Start-Jingle Audiodatei (null = noch nicht gewählt) */
    private var uriStartJingle: Uri? = null

    /** URI der gewählten End-Jingle Audiodatei (null = noch nicht gewählt) */
    private var uriEndJingle: Uri? = null

    /** Stunde der eingestellten Startzeit (-1 = noch nicht gewählt) */
    private var intHourStart: Int = -1

    /** Minute der eingestellten Startzeit (-1 = noch nicht gewählt) */
    private var intMinStart: Int = -1

    /** Aktuelle Start-Jingle Lautstärke als Wert zwischen 0.0 und 1.0 */
    private var floatVolLautstaerke: Float = 0.8f

    /** Separate End-Jingle Lautstärke als Wert zwischen 0.0 und 1.0 */
    private var floatVolEndJingle: Float = 0.8f

    /** SharedPreferences Schlüssel für persistente Einstellungen */
    private val stringPrefName = "TurnierTimerPrefs"

    // ─── Broadcast Receiver ─────────────────────────────────────────────────────

    /** Merkt sich welcher Test-Button gerade aktiv ist (für Reset nach Jingle-Ende) */
    private var activeTestButton: Button? = null

    /**
     * Empfängt die Meldung vom Service dass der Test-Jingle fertig ist.
     * Setzt den aktiven Test-Button zurück.
     */
    private val testDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread {
                activeTestButton?.let { resetTestButton(it) }
                activeTestButton = null
            }
        }
    }

    /**
     * Empfängt Status-Updates vom TimerService und zeigt sie in der UI an.
     * Wird aufgerufen wenn der Service einen neuen Spielstand sendet.
     */
    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stringSpielNr = intent?.getStringExtra(TimerService.EXTRA_SPIEL_NR) ?: return
            val stringNaechstes = intent.getStringExtra(TimerService.EXTRA_NAECHSTES) ?: ""
            val stringEndJingleInfo = intent.getStringExtra(TimerService.EXTRA_END_JINGLE) ?: ""
            val boolTimerLaeuft = intent.getBooleanExtra(TimerService.EXTRA_TIMER_LAEUFT, false)

            runOnUiThread {
                findViewById<TextView>(R.id.tvSpielInfo).text = stringSpielNr
                findViewById<TextView>(R.id.tvNaechstesSpiel).text = stringNaechstes
                findViewById<TextView>(R.id.tvEndJingleInfo).text = stringEndJingleInfo
                // Buttons sperren/freigeben je nach Timer-Status
                setJingleButtonsEnabled(!boolTimerLaeuft)
            }
        }
    }

    // ─── Datei-Picker ────────────────────────────────────────────────────────────

    /**
     * Öffnet den Datei-Picker für den Start-Jingle.
     * Keine Längenprüfung nötig – der Start-Jingle hat keine Zeitbeschränkung.
     */
    private val startJinglePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            uriStartJingle = it
            val intSecDauer = getAudioDauer(it)
            // Button-Text aktualisieren mit Dateidauer als Feedback
            findViewById<Button>(R.id.btnStartJingle).text = "✅ Start-Jingle gewählt (${intSecDauer}s)"
        }
    }

    /**
     * Öffnet den Datei-Picker für den End-Jingle.
     * Prüft ob die Audiodatei kürzer ist als die verbleibende Spielzeit nach dem End-Jingle.
     */
    private val endJinglePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val intSecDauer = getAudioDauer(it)

            // Maximale Jingle-Dauer = Zeitslot minus End-Minuten (verbleibende Zeit bis Spielende)
            val intMinZeitSlot = findViewById<EditText>(R.id.etZeitslot).text.toString().toIntOrNull()
            val intMinEnd = findViewById<EditText>(R.id.etEndMinuten).text.toString().toIntOrNull()
            val intSecMaxDauer = if (intMinZeitSlot != null && intMinEnd != null) (intMinZeitSlot - intMinEnd) * 60 else Int.MAX_VALUE

            if (intSecDauer > 0 && intSecDauer > intSecMaxDauer) {
                // Datei zu lang – ablehnen und Nutzer informieren
                Toast.makeText(
                    this,
                    "⚠️ End-Jingle zu lang! Max. ${intSecMaxDauer}s, Datei: ${intSecDauer}s",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // Datei akzeptieren
                uriEndJingle = it
                findViewById<Button>(R.id.btnEndJingle).text = "✅ End-Jingle gewählt (${intSecDauer}s)"
            }
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        // ActionBar ausblenden damit der Content nicht überlappt wird
        supportActionBar?.hide()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        berechtigungenAnfragen()
        setupUhrzeitButton()
        setupJingleButtons()
        setupTestButtons()
        setupLautstaerkeRegler()
        setupStartStopButtons()
        registriereReceiver()
        registriereTestReceiver()
        // Gespeicherte Einstellungen beim Start laden
        ladeEinstellungen()
    }

    /**
     * Sperrt oder entsperrt alle Jingle-Auswahl- und Test-Buttons.
     * Wird beim Timer-Start gesperrt und beim Stop wieder freigegeben.
     */
    private fun setJingleButtonsEnabled(enabled: Boolean) {
        listOf(
            R.id.btnStartJingle,
            R.id.btnEndJingle,
            R.id.btnTestStartJingle,
            R.id.btnTestEndJingle
        ).forEach { id ->
            findViewById<Button>(id).isEnabled = enabled
        }
    }

    override fun onResume() {
        super.onResume()
        // Button-Zustand sofort aus dem Service-State wiederherstellen (bevor Broadcast ankommt)
        val timerLaeuft = getSharedPreferences("TimerServiceState", Context.MODE_PRIVATE)
            .getBoolean("S_LAEUFT", false)
        setJingleButtonsEnabled(!timerLaeuft)
        if (timerLaeuft) {
            findViewById<TextView>(R.id.tvStatus).text =
                "✅ Timer läuft! Start: %02d:%02d Uhr".format(intHourStart, intMinStart)
        }
        // Sofortigen Status-Update vom Service anfordern (füllt tvSpielInfo etc.)
        startService(Intent(this, TimerService::class.java).apply {
            action = TimerService.ACTION_STATUS_ANFRAGEN
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        // Receiver abmelden – sonst Memory Leak
        unregisterReceiver(timerReceiver)
        unregisterReceiver(testDoneReceiver)
    }

    // ─── Setup Methoden ──────────────────────────────────────────────────────────

    /**
     * Speichert alle aktuellen Einstellungen in SharedPreferences.
     */
    private fun speichereEinstellungen() {
        val prefsEditor = getSharedPreferences(stringPrefName, Context.MODE_PRIVATE).edit()
        prefsEditor.putInt("PREF_HOUR_START", intHourStart)
        prefsEditor.putInt("PREF_MIN_START", intMinStart)
        prefsEditor.putString("PREF_ZEITSLOT", findViewById<EditText>(R.id.etZeitslot).text.toString())
        prefsEditor.putString("PREF_END_MIN", findViewById<EditText>(R.id.etEndMinuten).text.toString())
        prefsEditor.putString("PREF_URI_START_JINGLE", uriStartJingle?.toString() ?: "")
        prefsEditor.putString("PREF_URI_END_JINGLE", uriEndJingle?.toString() ?: "")
        prefsEditor.putFloat("PREF_VOL", floatVolLautstaerke)
        prefsEditor.putFloat("PREF_VOL_END", floatVolEndJingle)
        prefsEditor.apply()
        Toast.makeText(this, "✅ Einstellungen gespeichert!", Toast.LENGTH_SHORT).show()
    }

    /**
     * Lädt gespeicherte Einstellungen aus SharedPreferences und befüllt die UI.
     */
    private fun ladeEinstellungen() {
        val prefs = getSharedPreferences(stringPrefName, Context.MODE_PRIVATE)

        // Startzeit laden
        val intHourGespeichert = prefs.getInt("PREF_HOUR_START", -1)
        val intMinGespeichert = prefs.getInt("PREF_MIN_START", -1)
        if (intHourGespeichert != -1) {
            intHourStart = intHourGespeichert
            intMinStart = intMinGespeichert
            findViewById<Button>(R.id.btnStartzeit).text =
                "⏰ %02d:%02d Uhr".format(intHourStart, intMinStart)
        }

        // Zeitslot und End-Minuten laden
        val stringZeitslot = prefs.getString("PREF_ZEITSLOT", "")
        val stringEndMin = prefs.getString("PREF_END_MIN", "")
        if (!stringZeitslot.isNullOrEmpty()) {
            findViewById<EditText>(R.id.etZeitslot).setText(stringZeitslot)
        }
        if (!stringEndMin.isNullOrEmpty()) {
            findViewById<EditText>(R.id.etEndMinuten).setText(stringEndMin)
        }

        // Jingle URIs laden
        val stringUriStart = prefs.getString("PREF_URI_START_JINGLE", "")
        val stringUriEnd = prefs.getString("PREF_URI_END_JINGLE", "")
        if (!stringUriStart.isNullOrEmpty()) {
            uriStartJingle = Uri.parse(stringUriStart)
            val intSecDauer = getAudioDauer(uriStartJingle!!)
            findViewById<Button>(R.id.btnStartJingle).text = "✅ Start-Jingle gewählt (${intSecDauer}s)"
        }
        if (!stringUriEnd.isNullOrEmpty()) {
            uriEndJingle = Uri.parse(stringUriEnd)
            val intSecDauer = getAudioDauer(uriEndJingle!!)
            findViewById<Button>(R.id.btnEndJingle).text = "✅ End-Jingle gewählt (${intSecDauer}s)"
        }

        // Lautstärke laden
        val floatVolGespeichert = prefs.getFloat("PREF_VOL", 0.8f)
        floatVolLautstaerke = floatVolGespeichert
        val audioManagerSystem = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val intVolMax = audioManagerSystem.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val intVolAkt = (floatVolGespeichert * intVolMax).toInt()
        audioManagerSystem.setStreamVolume(AudioManager.STREAM_MUSIC, intVolAkt, 0)
        findViewById<android.widget.SeekBar>(R.id.seekBarLautstaerke).progress = intVolAkt
        findViewById<TextView>(R.id.textViewLautstaerke).text = "Lautstärke Start-Jingle: $intVolAkt / $intVolMax"

        // End-Jingle Lautstärke laden
        val floatVolEndGespeichert = prefs.getFloat("PREF_VOL_END", 0.8f)
        floatVolEndJingle = floatVolEndGespeichert
        val intVolEndAkt = (floatVolEndGespeichert * intVolMax).toInt()
        findViewById<android.widget.SeekBar>(R.id.seekBarLautstaerkeEnd).progress = intVolEndAkt
        findViewById<TextView>(R.id.textViewLautstaerkeEnd).text = "Lautstärke End-Jingle: $intVolEndAkt / $intVolMax"
    }

    /**
     * Fragt Benachrichtigungs-Berechtigung an (nur ab Android 13 / TIRAMISU nötig).
     */
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

    /**
     * Richtet den Uhrzeit-Button ein.
     * Öffnet einen TimePickerDialog und speichert die gewählte Uhrzeit.
     */
    private fun setupUhrzeitButton() {
        findViewById<Button>(R.id.btnStartzeit).setOnClickListener {
            val calJetzt = Calendar.getInstance()
            TimePickerDialog(
                this,
                { _, intHour, intMin ->
                    intHourStart = intHour
                    intMinStart = intMin
                    // Button-Text mit gewählter Uhrzeit aktualisieren
                    findViewById<Button>(R.id.btnStartzeit).text = "⏰ %02d:%02d Uhr".format(intHour, intMin)
                },
                calJetzt.get(Calendar.HOUR_OF_DAY),
                calJetzt.get(Calendar.MINUTE),
                true // 24h-Format
            ).show()
        }
    }

    /**
     * Richtet die Jingle-Auswahl Buttons ein.
     * Öffnet jeweils den Datei-Picker für Audio-Dateien.
     */
    private fun setupJingleButtons() {
        findViewById<Button>(R.id.btnStartJingle).setOnClickListener {
            startJinglePicker.launch("audio/*")
        }
        findViewById<Button>(R.id.btnEndJingle).setOnClickListener {
            endJinglePicker.launch("audio/*")
        }
    }

    /**
     * Richtet die Test-Buttons ein.
     * Delegiert an den TimerService (gleicher Code-Pfad wie echte Jingles, BT-kompatibel).
     * Zweites Drücken stoppt den laufenden Jingle.
     */
    private fun setupTestButtons() {
        findViewById<Button>(R.id.btnTestStartJingle).setOnClickListener {
            if (uriStartJingle == null) {
                Toast.makeText(this, "Bitte zuerst Start-Jingle auswählen!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            testJingleViaService(uriStartJingle!!, it as Button, isStartJingle = true)
        }

        findViewById<Button>(R.id.btnTestEndJingle).setOnClickListener {
            if (uriEndJingle == null) {
                Toast.makeText(this, "Bitte zuerst End-Jingle auswählen!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            testJingleViaService(uriEndJingle!!, it as Button, isStartJingle = false)
        }
    }

    /**
     * Richtet beide Lautstärke-Regler ein (Start-Jingle und End-Jingle).
     * Der Start-Regler steuert auch den System-Stream-Pegel.
     * Der End-Regler steuert nur den Software-Gain des End-Jingle-Players.
     */
    private fun setupLautstaerkeRegler() {
        val audioManagerSystem = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val intVolMax = audioManagerSystem.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // ── Start-Jingle Slider ──────────────────────────────────────────────────
        val seekBarStart = findViewById<android.widget.SeekBar>(R.id.seekBarLautstaerke)
        val tvLautstaerke = findViewById<TextView>(R.id.textViewLautstaerke)
        val intVolAkt = audioManagerSystem.getStreamVolume(AudioManager.STREAM_MUSIC)

        seekBarStart.max = intVolMax
        seekBarStart.progress = intVolAkt
        tvLautstaerke.text = "Lautstärke Start-Jingle: $intVolAkt / $intVolMax"

        seekBarStart.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, intProgress: Int, boolFromUser: Boolean) {
                audioManagerSystem.setStreamVolume(AudioManager.STREAM_MUSIC, intProgress, 0)
                tvLautstaerke.text = "Lautstärke Start-Jingle: $intProgress / $intVolMax"
                floatVolLautstaerke = intProgress / intVolMax.toFloat()

                val intentLautstaerke = Intent(this@MainActivity, TimerService::class.java).apply {
                    action = TimerService.ACTION_LAUTSTAERKE
                    putExtra(TimerService.EXTRA_LAUTSTAERKE_WERT, floatVolLautstaerke)
                }
                startService(intentLautstaerke)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // ── End-Jingle Slider ────────────────────────────────────────────────────
        val seekBarEnd = findViewById<android.widget.SeekBar>(R.id.seekBarLautstaerkeEnd)
        val tvLautstaerkeEnd = findViewById<TextView>(R.id.textViewLautstaerkeEnd)
        val intVolEndAkt = (floatVolEndJingle * intVolMax).toInt()

        seekBarEnd.max = intVolMax
        seekBarEnd.progress = intVolEndAkt
        tvLautstaerkeEnd.text = "Lautstärke End-Jingle: $intVolEndAkt / $intVolMax"

        seekBarEnd.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, intProgress: Int, boolFromUser: Boolean) {
                tvLautstaerkeEnd.text = "Lautstärke End-Jingle: $intProgress / $intVolMax"
                floatVolEndJingle = intProgress / intVolMax.toFloat()

                // Live an laufenden Service senden
                val intentEndVol = Intent(this@MainActivity, TimerService::class.java).apply {
                    action = TimerService.ACTION_LAUTSTAERKE_END
                    putExtra(TimerService.EXTRA_LAUTSTAERKE_END_WERT, floatVolEndJingle)
                }
                startService(intentEndVol)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    /**
     * Richtet Start- und Stop-Button ein.
     * Validiert alle Eingaben vor dem Start des Services.
     */
    private fun setupStartStopButtons() {
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val intMinZeitSlot = findViewById<EditText>(R.id.etZeitslot).text.toString().toIntOrNull()
            val intMinEnd = findViewById<EditText>(R.id.etEndMinuten).text.toString().toIntOrNull()

            // Eingaben in der richtigen Reihenfolge prüfen
            when {
                intHourStart == -1 ->
                    Toast.makeText(this, "Bitte Startzeit wählen!", Toast.LENGTH_SHORT).show()

                intMinZeitSlot == null || intMinZeitSlot <= 0 ->
                    Toast.makeText(this, "Bitte gültigen Zeitslot eingeben!", Toast.LENGTH_SHORT).show()

                intMinEnd == null || intMinEnd <= 0 ->
                    Toast.makeText(this, "Bitte End-Minuten eingeben!", Toast.LENGTH_SHORT).show()

                intMinEnd >= intMinZeitSlot ->
                    Toast.makeText(this, "End-Minuten müssen kleiner als Zeitslot sein!", Toast.LENGTH_SHORT).show()

                uriStartJingle == null ->
                    Toast.makeText(this, "Bitte Start-Jingle auswählen!", Toast.LENGTH_SHORT).show()

                uriEndJingle == null ->
                    Toast.makeText(this, "Bitte End-Jingle auswählen!", Toast.LENGTH_SHORT).show()

                else -> {
                    // End-Jingle Länge nochmal prüfen (Zeitslot könnte nachträglich geändert worden sein)
                    val intSecMaxDauer = (intMinZeitSlot - intMinEnd) * 60
                    val intSecEndDauer = getAudioDauer(uriEndJingle!!)

                    if (intSecEndDauer > 0 && intSecEndDauer > intSecMaxDauer) {
                        Toast.makeText(
                            this,
                            "⚠️ End-Jingle zu lang! Max. ${intSecMaxDauer}s, Datei: ${intSecEndDauer}s",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        starteService(intMinZeitSlot, intMinEnd)
                    }
                }
            }
        }

        // Timer stoppen und UI zurücksetzen
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, TimerService::class.java))
            findViewById<TextView>(R.id.tvStatus).text = "⏹ Timer gestoppt"
            findViewById<TextView>(R.id.tvSpielInfo).text = ""
            findViewById<TextView>(R.id.tvNaechstesSpiel).text = ""
            findViewById<TextView>(R.id.tvEndJingleInfo).text = ""
            setJingleButtonsEnabled(true)
        }

        // Einstellungen speichern
        findViewById<Button>(R.id.btnSpeichern).setOnClickListener {
            speichereEinstellungen()
        }
    }

    /**
     * Startet den TimerService mit allen nötigen Parametern.
     */
    private fun starteService(intMinZeitSlot: Int, intMinEnd: Int) {
        val intentService = Intent(this, TimerService::class.java).apply {
            putExtra("START_STUNDE", intHourStart)
            putExtra("START_MINUTE", intMinStart)
            putExtra("ZEITSLOT", intMinZeitSlot)
            putExtra("END_MINUTEN", intMinEnd)
            putExtra("START_JINGLE", uriStartJingle.toString())
            putExtra("END_JINGLE", uriEndJingle.toString())
            putExtra("LAUTSTAERKE", floatVolLautstaerke)
            putExtra("LAUTSTAERKE_END", floatVolEndJingle)
        }
        startForegroundService(intentService)
        setJingleButtonsEnabled(false)
        findViewById<TextView>(R.id.tvStatus).text =
            "✅ Timer läuft! Start: %02d:%02d Uhr".format(intHourStart, intMinStart)
    }

    /**
     * Registriert den BroadcastReceiver für Status-Updates vom Service.
     * Ab Android 13 muss RECEIVER_NOT_EXPORTED angegeben werden.
     */
    private fun registriereReceiver() {
        val intentFilterTimer = IntentFilter(TimerService.BROADCAST_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timerReceiver, intentFilterTimer, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(timerReceiver, intentFilterTimer)
        }
    }

    /**
     * Registriert den BroadcastReceiver für das Test-Jingle-Ende vom Service.
     */
    private fun registriereTestReceiver() {
        val filter = IntentFilter(TimerService.BROADCAST_TEST_DONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(testDoneReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(testDoneReceiver, filter)
        }
    }

    // ─── Hilfsmethoden ───────────────────────────────────────────────────────────

    /**
     * Liest die Dauer einer Audiodatei in Sekunden aus.
     * Gibt 0 zurück wenn die Datei nicht gelesen werden kann.
     */
    private fun getAudioDauer(uriAudio: Uri): Int {
        return try {
            val retrieverMetaData = android.media.MediaMetadataRetriever()
            retrieverMetaData.setDataSource(this, uriAudio)
            val longMsDauer = retrieverMetaData.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            retrieverMetaData.release()
            (longMsDauer / 1000).toInt()
        } catch (e: Exception) {
            Log.e("TurnierTimer", "Fehler beim Lesen der Audiodauer: ${e.message}")
            0
        }
    }

    /**
     * Sendet einen Test-Jingle-Intent an den Service.
     * Zweites Drücken des Buttons stoppt den laufenden Jingle (Service erkennt isPlaying).
     * Der Service nutzt denselben Code-Pfad wie echte Jingles → BT-kompatibel.
     *
     * @param uriAudio URI der Audiodatei
     * @param buttonTest Der Button der gedrückt wurde (wird optisch umgeschaltet)
     * @param isStartJingle true = Start-Jingle-Lautstärke, false = End-Jingle-Lautstärke
     */
    private fun testJingleViaService(uriAudio: Uri, buttonTest: Button, isStartJingle: Boolean) {
        val isPlaying = activeTestButton != null

        if (isPlaying) {
            val intentStop = Intent(this, TimerService::class.java).apply {
                action = TimerService.ACTION_TEST_JINGLE
                putExtra(TimerService.EXTRA_TEST_JINGLE_URI, uriAudio.toString())
                putExtra("IS_START_JINGLE", isStartJingle)
            }
            startService(intentStop)
        } else {
            activeTestButton = buttonTest
            buttonTest.text = "⏹ Stoppen"
            buttonTest.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#F44336")
            )
            val intentPlay = Intent(this, TimerService::class.java).apply {
                action = TimerService.ACTION_TEST_JINGLE
                putExtra(TimerService.EXTRA_TEST_JINGLE_URI, uriAudio.toString())
                putExtra("IS_START_JINGLE", isStartJingle)
            }
            startService(intentPlay)
        }
    }

    /**
     * Setzt einen Test-Button auf seinen ursprünglichen Zustand zurück.
     * Bestimmt den richtigen Text anhand der Button-ID.
     */
    private fun resetTestButton(buttonTest: Button) {
        runOnUiThread {
            buttonTest.text = if (buttonTest.id == R.id.btnTestStartJingle) "▶ Start-Jingle testen" else "▶ End-Jingle testen"
            buttonTest.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#2196F3")
            )
        }
    }
}