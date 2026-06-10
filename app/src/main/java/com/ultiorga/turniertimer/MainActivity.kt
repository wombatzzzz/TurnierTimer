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

    /** URI der gewählten Letzte-x-Minuten-Jingle Audiodatei (null = noch nicht gewählt) */
    private var uriLetzteMinJingle: Uri? = null

    /** URI der gewählten Schluss-Jingle Audiodatei (null = noch nicht gewählt) */
    private var uriSchlussJingle: Uri? = null

    /** Stunde der eingestellten Startzeit (-1 = noch nicht gewählt) */
    private var intHourStart: Int = -1

    /** Minute der eingestellten Startzeit (-1 = noch nicht gewählt) */
    private var intMinStart: Int = -1

    /** Aktuelle Start-Jingle Lautstärke als Wert zwischen 0.0 und 1.0 */
    private var floatVolLautstaerke: Float = 0.8f

    /** Software-Gain für alle drei Jingles als Wert zwischen 0.0 und 1.0 */
    private var floatVolJingle: Float = 0.8f

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
            val stringLetzteMinJingleInfo = intent.getStringExtra(TimerService.EXTRA_LETZTE_MIN_JINGLE) ?: ""
            val boolTimerLaeuft = intent.getBooleanExtra(TimerService.EXTRA_TIMER_LAEUFT, false)

            val stringSchlussJingleInfo = intent.getStringExtra(TimerService.EXTRA_SCHLUSS_JINGLE) ?: ""
            runOnUiThread {
                findViewById<TextView>(R.id.tvSpielInfo).text = stringSpielNr
                findViewById<TextView>(R.id.tvNaechstesSpiel).text = stringNaechstes
                findViewById<TextView>(R.id.tvLetzteMinJingleInfo).text = stringLetzteMinJingleInfo
                findViewById<TextView>(R.id.tvSchlussJingleInfo).text = stringSchlussJingleInfo
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
     * Öffnet den Datei-Picker für den Schluss-Jingle.
     * Prüft ob die Audiodatei kürzer ist als die verbleibende Zeit nach dem Schluss-Jingle.
     */
    private val schlussJinglePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val intSecDauer = getAudioDauer(it)
            val intMinZeitSlot = findViewById<EditText>(R.id.etZeitslot).text.toString().toIntOrNull()
            val intMinSchluss = findViewById<EditText>(R.id.etSchlussMinuten).text.toString().toIntOrNull()
            val intSecMaxDauer = if (intMinZeitSlot != null && intMinSchluss != null) (intMinZeitSlot - intMinSchluss) * 60 else Int.MAX_VALUE

            if (intSecDauer > 0 && intSecDauer > intSecMaxDauer) {
                Toast.makeText(
                    this,
                    "⚠️ Schluss-Jingle zu lang! Max. ${intSecMaxDauer}s, Datei: ${intSecDauer}s",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                uriSchlussJingle = it
                findViewById<Button>(R.id.btnSchlussJingle).text = "✅ Schluss-Jingle gewählt (${intSecDauer}s)"
            }
        }
    }

    /**
     * Öffnet den Datei-Picker für den Letzte-x-Minuten-Jingle.
     * Prüft ob die Audiodatei kürzer ist als die verbleibende Spielzeit nach dem Letzte-x-Minuten-Jingle.
     */
    private val letzteMinJinglePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val intSecDauer = getAudioDauer(it)

            // Maximale Jingle-Dauer = Zeitslot minus End-Minuten (verbleibende Zeit bis Spielende)
            val intMinZeitSlot = findViewById<EditText>(R.id.etZeitslot).text.toString().toIntOrNull()
            val intMinLetzteMin = findViewById<EditText>(R.id.etLetzteMinMinuten).text.toString().toIntOrNull()
            val intSecMaxDauer = if (intMinZeitSlot != null && intMinLetzteMin != null) (intMinZeitSlot - intMinLetzteMin) * 60 else Int.MAX_VALUE

            if (intSecDauer > 0 && intSecDauer > intSecMaxDauer) {
                // Datei zu lang – ablehnen und Nutzer informieren
                Toast.makeText(
                    this,
                    "⚠️ Letzte-x-Minuten-Jingle zu lang! Max. ${intSecMaxDauer}s, Datei: ${intSecDauer}s",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // Datei akzeptieren
                uriLetzteMinJingle = it
                findViewById<Button>(R.id.btnLetzteMinJingle).text = "✅ Letzte-x-Minuten-Jingle gewählt (${intSecDauer}s)"
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
            R.id.btnLetzteMinJingle,
            R.id.btnSchlussJingle,
            R.id.btnTestStartJingle,
            R.id.btnTestLetzteMinJingle,
            R.id.btnTestSchlussJingle
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
        prefsEditor.putString("PREF_END_MIN", findViewById<EditText>(R.id.etLetzteMinMinuten).text.toString())
        prefsEditor.putString("PREF_SCHLUSS_MIN", findViewById<EditText>(R.id.etSchlussMinuten).text.toString())
        prefsEditor.putString("PREF_URI_START_JINGLE", uriStartJingle?.toString() ?: "")
        prefsEditor.putString("PREF_URI_END_JINGLE", uriLetzteMinJingle?.toString() ?: "")
        prefsEditor.putString("PREF_URI_SCHLUSS_JINGLE", uriSchlussJingle?.toString() ?: "")
        prefsEditor.putFloat("PREF_VOL", floatVolLautstaerke)
        prefsEditor.putFloat("PREF_VOL_JINGLE", floatVolJingle)
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

        // Zeitslot, End-Minuten und Schluss-Minuten laden
        val stringZeitslot = prefs.getString("PREF_ZEITSLOT", "")
        val stringEndMin = prefs.getString("PREF_END_MIN", "")
        val stringSchlussMin = prefs.getString("PREF_SCHLUSS_MIN", "")
        if (!stringZeitslot.isNullOrEmpty()) {
            findViewById<EditText>(R.id.etZeitslot).setText(stringZeitslot)
        }
        if (!stringEndMin.isNullOrEmpty()) {
            findViewById<EditText>(R.id.etLetzteMinMinuten).setText(stringEndMin)
        }
        if (!stringSchlussMin.isNullOrEmpty()) {
            findViewById<EditText>(R.id.etSchlussMinuten).setText(stringSchlussMin)
        }

        // Jingle URIs laden
        val stringUriStart = prefs.getString("PREF_URI_START_JINGLE", "")
        val stringUriEnd = prefs.getString("PREF_URI_END_JINGLE", "")
        val stringUriSchluss = prefs.getString("PREF_URI_SCHLUSS_JINGLE", "")
        if (!stringUriStart.isNullOrEmpty()) {
            uriStartJingle = Uri.parse(stringUriStart)
            val intSecDauer = getAudioDauer(uriStartJingle!!)
            findViewById<Button>(R.id.btnStartJingle).text = "✅ Start-Jingle gewählt (${intSecDauer}s)"
        }
        if (!stringUriEnd.isNullOrEmpty()) {
            uriLetzteMinJingle = Uri.parse(stringUriEnd)
            val intSecDauer = getAudioDauer(uriLetzteMinJingle!!)
            findViewById<Button>(R.id.btnLetzteMinJingle).text = "✅ Letzte-x-Minuten-Jingle gewählt (${intSecDauer}s)"
        }
        if (!stringUriSchluss.isNullOrEmpty()) {
            uriSchlussJingle = Uri.parse(stringUriSchluss)
            val intSecDauer = getAudioDauer(uriSchlussJingle!!)
            findViewById<Button>(R.id.btnSchlussJingle).text = "✅ Schluss-Jingle gewählt (${intSecDauer}s)"
        }

        // Lautstärke laden
        val floatVolGespeichert = prefs.getFloat("PREF_VOL", 0.8f)
        floatVolLautstaerke = floatVolGespeichert
        val audioManagerSystem = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val intVolMax = audioManagerSystem.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val intVolAkt = (floatVolGespeichert * intVolMax).toInt()
        audioManagerSystem.setStreamVolume(AudioManager.STREAM_MUSIC, intVolAkt, 0)
        findViewById<android.widget.SeekBar>(R.id.seekBarLautstaerke).progress = intVolAkt
        findViewById<TextView>(R.id.textViewLautstaerke).text = "Medien Lautstärke: $intVolAkt / $intVolMax"

        // Jingle Lautstärke laden
        val floatVolJingleGespeichert = prefs.getFloat("PREF_VOL_JINGLE", 0.8f)
        floatVolJingle = floatVolJingleGespeichert
        val intVolJingleAkt = (floatVolJingleGespeichert * intVolMax).toInt()
        findViewById<android.widget.SeekBar>(R.id.seekBarJingleLautstaerke).progress = intVolJingleAkt
        findViewById<TextView>(R.id.textViewJingleLautstaerke).text = "Jingle Lautstärke: $intVolJingleAkt / $intVolMax"
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
        findViewById<Button>(R.id.btnLetzteMinJingle).setOnClickListener {
            letzteMinJinglePicker.launch("audio/*")
        }
        findViewById<Button>(R.id.btnSchlussJingle).setOnClickListener {
            schlussJinglePicker.launch("audio/*")
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
            testJingleViaService(uriStartJingle!!, it as Button, jingleTyp = JingleTyp.START)
        }

        findViewById<Button>(R.id.btnTestLetzteMinJingle).setOnClickListener {
            if (uriLetzteMinJingle == null) {
                Toast.makeText(this, "Bitte zuerst Letzte-x-Minuten-Jingle auswählen!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            testJingleViaService(uriLetzteMinJingle!!, it as Button, jingleTyp = JingleTyp.END)
        }

        findViewById<Button>(R.id.btnTestSchlussJingle).setOnClickListener {
            if (uriSchlussJingle == null) {
                Toast.makeText(this, "Bitte zuerst Schluss-Jingle auswählen!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            testJingleViaService(uriSchlussJingle!!, it as Button, jingleTyp = JingleTyp.SCHLUSS)
        }
    }

    private fun setupLautstaerkeRegler() {
        val audioManagerSystem = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val intVolMax = audioManagerSystem.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // ── Medien Lautstärke (System-Stream) ───────────────────────────────────
        val seekBarMedia = findViewById<android.widget.SeekBar>(R.id.seekBarLautstaerke)
        val tvLautstaerke = findViewById<TextView>(R.id.textViewLautstaerke)
        val intVolAkt = audioManagerSystem.getStreamVolume(AudioManager.STREAM_MUSIC)

        seekBarMedia.max = intVolMax
        seekBarMedia.progress = intVolAkt
        tvLautstaerke.text = "Medien Lautstärke: $intVolAkt / $intVolMax"

        seekBarMedia.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, intProgress: Int, boolFromUser: Boolean) {
                audioManagerSystem.setStreamVolume(AudioManager.STREAM_MUSIC, intProgress, 0)
                tvLautstaerke.text = "Medien Lautstärke: $intProgress / $intVolMax"
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

        // ── Jingle Lautstärke (Software-Gain für alle Jingles) ──────────────────
        val seekBarJingle = findViewById<android.widget.SeekBar>(R.id.seekBarJingleLautstaerke)
        val tvJingleLautstaerke = findViewById<TextView>(R.id.textViewJingleLautstaerke)
        val intVolJingleAkt = (floatVolJingle * intVolMax).toInt()

        seekBarJingle.max = intVolMax
        seekBarJingle.progress = intVolJingleAkt
        tvJingleLautstaerke.text = "Jingle Lautstärke: $intVolJingleAkt / $intVolMax"

        seekBarJingle.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, intProgress: Int, boolFromUser: Boolean) {
                tvJingleLautstaerke.text = "Jingle Lautstärke: $intProgress / $intVolMax"
                floatVolJingle = intProgress / intVolMax.toFloat()

                val intentJingleVol = Intent(this@MainActivity, TimerService::class.java).apply {
                    action = TimerService.ACTION_LAUTSTAERKE_JINGLE
                    putExtra(TimerService.EXTRA_LAUTSTAERKE_JINGLE_WERT, floatVolJingle)
                }
                startService(intentJingleVol)
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
            val intMinLetzteMin = findViewById<EditText>(R.id.etLetzteMinMinuten).text.toString().toIntOrNull()
            val intMinSchluss = findViewById<EditText>(R.id.etSchlussMinuten).text.toString().toIntOrNull()

            when {
                intHourStart == -1 ->
                    Toast.makeText(this, "Bitte Startzeit wählen!", Toast.LENGTH_SHORT).show()

                intMinZeitSlot == null || intMinZeitSlot <= 0 ->
                    Toast.makeText(this, "Bitte gültigen Zeitslot eingeben!", Toast.LENGTH_SHORT).show()

                intMinLetzteMin == null || intMinLetzteMin <= 0 ->
                    Toast.makeText(this, "Bitte End-Minuten eingeben!", Toast.LENGTH_SHORT).show()

                intMinLetzteMin >= intMinZeitSlot ->
                    Toast.makeText(this, "End-Minuten müssen kleiner als Zeitslot sein!", Toast.LENGTH_SHORT).show()

                intMinSchluss == null || intMinSchluss <= 0 ->
                    Toast.makeText(this, "Bitte Schluss-Minuten eingeben!", Toast.LENGTH_SHORT).show()

                intMinSchluss >= intMinZeitSlot ->
                    Toast.makeText(this, "Schluss-Minuten müssen kleiner als Zeitslot sein!", Toast.LENGTH_SHORT).show()

                intMinSchluss <= intMinLetzteMin ->
                    Toast.makeText(this, "Schluss-Minuten müssen größer als End-Minuten sein!", Toast.LENGTH_SHORT).show()

                uriStartJingle == null ->
                    Toast.makeText(this, "Bitte Start-Jingle auswählen!", Toast.LENGTH_SHORT).show()

                uriLetzteMinJingle == null ->
                    Toast.makeText(this, "Bitte Letzte-x-Minuten-Jingle auswählen!", Toast.LENGTH_SHORT).show()

                uriSchlussJingle == null ->
                    Toast.makeText(this, "Bitte Schluss-Jingle auswählen!", Toast.LENGTH_SHORT).show()

                else -> {
                    val intSecMaxEndDauer = (intMinZeitSlot - intMinLetzteMin) * 60
                    val intSecEndDauer = getAudioDauer(uriLetzteMinJingle!!)
                    val intSecMaxSchlussDauer = (intMinZeitSlot - intMinSchluss) * 60
                    val intSecSchlussDauer = getAudioDauer(uriSchlussJingle!!)

                    when {
                        intSecEndDauer > 0 && intSecEndDauer > intSecMaxEndDauer ->
                            Toast.makeText(
                                this,
                                "⚠️ Letzte-x-Minuten-Jingle zu lang! Max. ${intSecMaxEndDauer}s, Datei: ${intSecEndDauer}s",
                                Toast.LENGTH_LONG
                            ).show()
                        intSecSchlussDauer > 0 && intSecSchlussDauer > intSecMaxSchlussDauer ->
                            Toast.makeText(
                                this,
                                "⚠️ Schluss-Jingle zu lang! Max. ${intSecMaxSchlussDauer}s, Datei: ${intSecSchlussDauer}s",
                                Toast.LENGTH_LONG
                            ).show()
                        else -> starteService(intMinZeitSlot, intMinLetzteMin, intMinSchluss)
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
            findViewById<TextView>(R.id.tvLetzteMinJingleInfo).text = ""
            findViewById<TextView>(R.id.tvSchlussJingleInfo).text = ""
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
    private fun starteService(intMinZeitSlot: Int, intMinLetzteMin: Int, intMinSchluss: Int) {
        val intentService = Intent(this, TimerService::class.java).apply {
            putExtra("START_STUNDE", intHourStart)
            putExtra("START_MINUTE", intMinStart)
            putExtra("ZEITSLOT", intMinZeitSlot)
            putExtra("LETZTE_MIN_MINUTEN", intMinLetzteMin)
            putExtra("SCHLUSS_MINUTEN", intMinSchluss)
            putExtra("START_JINGLE", uriStartJingle.toString())
            putExtra("LETZTE_MIN_JINGLE", uriLetzteMinJingle.toString())
            putExtra("SCHLUSS_JINGLE", uriSchlussJingle.toString())
            putExtra("LAUTSTAERKE", floatVolLautstaerke)
            putExtra("LAUTSTAERKE_JINGLE", floatVolJingle)
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

    private enum class JingleTyp { START, END, SCHLUSS }

    /**
     * Sendet einen Test-Jingle-Intent an den Service.
     * Zweites Drücken des Buttons stoppt den laufenden Jingle.
     */
    private fun testJingleViaService(uriAudio: Uri, buttonTest: Button, jingleTyp: JingleTyp) {
        val isPlaying = activeTestButton != null

        if (isPlaying) {
            val intentStop = Intent(this, TimerService::class.java).apply {
                action = TimerService.ACTION_TEST_JINGLE
                putExtra(TimerService.EXTRA_TEST_JINGLE_URI, uriAudio.toString())
                putExtra("JINGLE_TYP", jingleTyp.name)
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
                putExtra("JINGLE_TYP", jingleTyp.name)
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
            buttonTest.text = when (buttonTest.id) {
                R.id.btnTestStartJingle -> "▶ Start-Jingle testen"
                R.id.btnTestLetzteMinJingle -> "▶ Letzte-x-Minuten-Jingle testen"
                else -> "▶ Schluss-Jingle testen"
            }
            buttonTest.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#2196F3")
            )
        }
    }
}