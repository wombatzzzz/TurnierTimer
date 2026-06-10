package com.ultiorga.turniertimer

import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar

class EinstellungenActivity : AppCompatActivity() {

    private var uriStartJingle: Uri? = null
    private var uriLetzteMinJingle: Uri? = null
    private var uriSchlussJingle: Uri? = null
    private var intHourStart: Int = -1
    private var intMinStart: Int = -1
    private var activeTestButton: Button? = null
    private val stringPrefName = "TurnierTimerPrefs"

    private val testDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread {
                activeTestButton?.let { resetTestButton(it) }
                activeTestButton = null
            }
        }
    }

    private val startJinglePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            uriStartJingle = it
            val intSecDauer = getAudioDauer(it)
            findViewById<Button>(R.id.btnStartJingle).text = "✅ Start-Jingle gewählt (${intSecDauer}s)"
            getSharedPreferences(stringPrefName, Context.MODE_PRIVATE).edit()
                .putString("PREF_URI_START_JINGLE", it.toString()).apply()
        }
    }

    private val letzteMinJinglePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val intSecDauer = getAudioDauer(it)
            val intMinZeitSlot = findViewById<EditText>(R.id.etZeitslot).text.toString().toIntOrNull()
            val intMinLetzteMin = findViewById<EditText>(R.id.etLetzteMinMinuten).text.toString().toIntOrNull()
            val intSecMaxDauer = if (intMinZeitSlot != null && intMinLetzteMin != null)
                (intMinZeitSlot - intMinLetzteMin) * 60 else Int.MAX_VALUE

            if (intSecDauer > 0 && intSecDauer > intSecMaxDauer) {
                Toast.makeText(
                    this,
                    "⚠️ Letzte-x-Minuten-Jingle zu lang! Max. ${intSecMaxDauer}s, Datei: ${intSecDauer}s",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                uriLetzteMinJingle = it
                findViewById<Button>(R.id.btnLetzteMinJingle).text =
                    "✅ Letzte-x-Minuten-Jingle gewählt (${intSecDauer}s)"
                getSharedPreferences(stringPrefName, Context.MODE_PRIVATE).edit()
                    .putString("PREF_URI_END_JINGLE", it.toString()).apply()
            }
        }
    }

    private val schlussJinglePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val intSecDauer = getAudioDauer(it)
            val intMinZeitSlot = findViewById<EditText>(R.id.etZeitslot).text.toString().toIntOrNull()
            val intMinSchluss = findViewById<EditText>(R.id.etSchlussMinuten).text.toString().toIntOrNull()
            val intSecMaxDauer = if (intMinZeitSlot != null && intMinSchluss != null)
                (intMinZeitSlot - intMinSchluss) * 60 else Int.MAX_VALUE

            if (intSecDauer > 0 && intSecDauer > intSecMaxDauer) {
                Toast.makeText(
                    this,
                    "⚠️ Schluss-Jingle zu lang! Max. ${intSecMaxDauer}s, Datei: ${intSecDauer}s",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                uriSchlussJingle = it
                findViewById<Button>(R.id.btnSchlussJingle).text =
                    "✅ Schluss-Jingle gewählt (${intSecDauer}s)"
                getSharedPreferences(stringPrefName, Context.MODE_PRIVATE).edit()
                    .putString("PREF_URI_SCHLUSS_JINGLE", it.toString()).apply()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_einstellungen)
        supportActionBar?.title = "Einstellungen"

        setupUhrzeitButton()
        setupJingleButtons()
        setupTestButtons()
        setupSpeichernButton()
        ladeEinstellungen()
    }

    override fun onResume() {
        super.onResume()
        ladeEinstellungen()
        val timerLaeuft = getSharedPreferences("TimerServiceState", Context.MODE_PRIVATE)
            .getBoolean("S_LAEUFT", false)
        setTestButtonsEnabled(!timerLaeuft)

        val filter = IntentFilter(TimerService.BROADCAST_TEST_DONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(testDoneReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(testDoneReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(testDoneReceiver) } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        speichereEinstellungen(showToast = false)
    }

    private fun setupUhrzeitButton() {
        findViewById<Button>(R.id.btnStartzeit).setOnClickListener {
            val calJetzt = Calendar.getInstance()
            TimePickerDialog(
                this,
                { _, intHour, intMin ->
                    intHourStart = intHour
                    intMinStart = intMin
                    findViewById<Button>(R.id.btnStartzeit).text =
                        "⏰ %02d:%02d Uhr".format(intHour, intMin)
                    getSharedPreferences(stringPrefName, Context.MODE_PRIVATE).edit()
                        .putInt("PREF_HOUR_START", intHour)
                        .putInt("PREF_MIN_START", intMin)
                        .apply()
                },
                calJetzt.get(Calendar.HOUR_OF_DAY),
                calJetzt.get(Calendar.MINUTE),
                true
            ).show()
        }
    }

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

    private fun setupTestButtons() {
        findViewById<Button>(R.id.btnTestStartJingle).setOnClickListener {
            if (uriStartJingle == null) {
                Toast.makeText(this, "Bitte zuerst Start-Jingle auswählen!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            testJingleViaService(uriStartJingle!!, it as Button, JingleTyp.START)
        }
        findViewById<Button>(R.id.btnTestLetzteMinJingle).setOnClickListener {
            if (uriLetzteMinJingle == null) {
                Toast.makeText(this, "Bitte zuerst Letzte-x-Minuten-Jingle auswählen!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            testJingleViaService(uriLetzteMinJingle!!, it as Button, JingleTyp.END)
        }
        findViewById<Button>(R.id.btnTestSchlussJingle).setOnClickListener {
            if (uriSchlussJingle == null) {
                Toast.makeText(this, "Bitte zuerst Schluss-Jingle auswählen!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            testJingleViaService(uriSchlussJingle!!, it as Button, JingleTyp.SCHLUSS)
        }
    }

    private fun setupSpeichernButton() {
        findViewById<Button>(R.id.btnSpeichern).setOnClickListener {
            speichereEinstellungen(showToast = true)
        }
    }

    private fun speichereEinstellungen(showToast: Boolean) {
        val prefsEditor = getSharedPreferences(stringPrefName, Context.MODE_PRIVATE).edit()
        prefsEditor.putInt("PREF_HOUR_START", intHourStart)
        prefsEditor.putInt("PREF_MIN_START", intMinStart)
        prefsEditor.putString("PREF_ZEITSLOT", findViewById<EditText>(R.id.etZeitslot).text.toString())
        prefsEditor.putString("PREF_END_MIN", findViewById<EditText>(R.id.etLetzteMinMinuten).text.toString())
        prefsEditor.putString("PREF_SCHLUSS_MIN", findViewById<EditText>(R.id.etSchlussMinuten).text.toString())
        prefsEditor.putString("PREF_URI_START_JINGLE", uriStartJingle?.toString() ?: "")
        prefsEditor.putString("PREF_URI_END_JINGLE", uriLetzteMinJingle?.toString() ?: "")
        prefsEditor.putString("PREF_URI_SCHLUSS_JINGLE", uriSchlussJingle?.toString() ?: "")
        prefsEditor.apply()
        if (showToast) Toast.makeText(this, "✅ Einstellungen gespeichert!", Toast.LENGTH_SHORT).show()
    }

    private fun ladeEinstellungen() {
        val prefs = getSharedPreferences(stringPrefName, Context.MODE_PRIVATE)

        val intHourGespeichert = prefs.getInt("PREF_HOUR_START", -1)
        if (intHourGespeichert != -1) {
            intHourStart = intHourGespeichert
            intMinStart = prefs.getInt("PREF_MIN_START", 0)
            findViewById<Button>(R.id.btnStartzeit).text =
                "⏰ %02d:%02d Uhr".format(intHourStart, intMinStart)
        }

        val stringZeitslot = prefs.getString("PREF_ZEITSLOT", "")
        val stringEndMin = prefs.getString("PREF_END_MIN", "")
        val stringSchlussMin = prefs.getString("PREF_SCHLUSS_MIN", "")
        if (!stringZeitslot.isNullOrEmpty()) findViewById<EditText>(R.id.etZeitslot).setText(stringZeitslot)
        if (!stringEndMin.isNullOrEmpty()) findViewById<EditText>(R.id.etLetzteMinMinuten).setText(stringEndMin)
        if (!stringSchlussMin.isNullOrEmpty()) findViewById<EditText>(R.id.etSchlussMinuten).setText(stringSchlussMin)

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
            findViewById<Button>(R.id.btnLetzteMinJingle).text =
                "✅ Letzte-x-Minuten-Jingle gewählt (${intSecDauer}s)"
        }
        if (!stringUriSchluss.isNullOrEmpty()) {
            uriSchlussJingle = Uri.parse(stringUriSchluss)
            val intSecDauer = getAudioDauer(uriSchlussJingle!!)
            findViewById<Button>(R.id.btnSchlussJingle).text = "✅ Schluss-Jingle gewählt (${intSecDauer}s)"
        }
    }

    private fun setTestButtonsEnabled(enabled: Boolean) {
        listOf(
            R.id.btnTestStartJingle,
            R.id.btnTestLetzteMinJingle,
            R.id.btnTestSchlussJingle
        ).forEach { id ->
            val btn = findViewById<Button>(id)
            btn.isEnabled = enabled
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (enabled) android.graphics.Color.parseColor("#2196F3")
                else android.graphics.Color.parseColor("#AAAAAA")
            )
        }
    }

    private fun testJingleViaService(uriAudio: Uri, buttonTest: Button, jingleTyp: JingleTyp) {
        val isPlaying = activeTestButton != null
        if (isPlaying) {
            startService(Intent(this, TimerService::class.java).apply {
                action = TimerService.ACTION_TEST_JINGLE
                putExtra(TimerService.EXTRA_TEST_JINGLE_URI, uriAudio.toString())
                putExtra("JINGLE_TYP", jingleTyp.name)
            })
        } else {
            activeTestButton = buttonTest
            buttonTest.text = "⏹ Stoppen"
            buttonTest.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#F44336")
            )
            startService(Intent(this, TimerService::class.java).apply {
                action = TimerService.ACTION_TEST_JINGLE
                putExtra(TimerService.EXTRA_TEST_JINGLE_URI, uriAudio.toString())
                putExtra("JINGLE_TYP", jingleTyp.name)
            })
        }
    }

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
}
