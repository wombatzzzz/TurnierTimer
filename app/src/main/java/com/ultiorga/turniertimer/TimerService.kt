package com.ultiorga.turniertimer

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*

class TimerService : Service() {

    // ─── Konstanten ─────────────────────────────────────────────────────────────

    companion object {
        /** Broadcast-Action für Status-Updates an die MainActivity */
        const val BROADCAST_ACTION = "com.ultiorga.turniertimer.TIMER_UPDATE"

        /** Extra: Aktuelle Spielnummer als Text */
        const val EXTRA_SPIEL_NR = "spiel_nr"

        /** Extra: Uhrzeit des nächsten Spiels als Text */
        const val EXTRA_NAECHSTES = "stringNaechstes"

        /** Extra: Uhrzeit des Letzte-x-Minuten-Jingles als Text */
        const val EXTRA_LETZTE_MIN_JINGLE = "end_jingle_info"

        /** Intent-Action zum Live-Aktualisieren der Lautstärke ohne Service-Neustart */
        const val ACTION_LAUTSTAERKE = "com.ultiorga.turniertimer.floatVolLautstaerke"

        /** Extra: Lautstärke als Float zwischen 0.0 und 1.0 */
        const val EXTRA_LAUTSTAERKE_WERT = "floatVolLautstaerke_wert"

        /** Intent-Action zum Live-Aktualisieren der Jingle-Lautstärke (alle drei Jingles) */
        const val ACTION_LAUTSTAERKE_JINGLE = "com.ultiorga.turniertimer.ACTION_LAUTSTAERKE_JINGLE"

        /** Extra: Jingle-Lautstärke als Float zwischen 0.0 und 1.0 */
        const val EXTRA_LAUTSTAERKE_JINGLE_WERT = "floatVolJingle_wert"

        /** Intent-Action zum Testen eines Jingles über den Service (BT-kompatibel) */
        const val ACTION_TEST_JINGLE = "com.ultiorga.turniertimer.ACTION_TEST_JINGLE"

        /** Extra: URI des zu testenden Jingles als String */
        const val EXTRA_TEST_JINGLE_URI = "test_jingle_uri"

        /** Broadcast-Action: wird gesendet wenn der Test-Jingle fertig ist */
        const val BROADCAST_TEST_DONE = "com.ultiorga.turniertimer.TEST_JINGLE_DONE"

        /** Extra: Boolean – true wenn der Timer aktiv läuft (für Button-Sperr-Logik in MainActivity) */
        const val EXTRA_TIMER_LAEUFT = "timer_laeuft"

        /** Intent-Action: MainActivity fordert sofortigen Status-Update vom Service an */
        const val ACTION_STATUS_ANFRAGEN = "com.ultiorga.turniertimer.ACTION_STATUS_ANFRAGEN"

        /** Extra: Uhrzeit des Schluss-Jingles als Text */
        const val EXTRA_SCHLUSS_JINGLE = "schluss_jingle_info"
    }

    // ─── Notification ────────────────────────────────────────────────────────────

    /** ID des Notification-Channels */
    private val CHANNEL_ID = "TurnierTimerChannel"

    /** ID der dauerhaften Foreground-Notification */
    private val NOTIF_ID = 1

    // ─── Zustand ─────────────────────────────────────────────────────────────────

    /** Timer für das periodische Auslösen der Jingles */
    private var timerSpiele: Timer? = null

    /** MediaPlayer für die Jingle-Wiedergabe */
    private var mediaPlayerJingle: MediaPlayer? = null

    /** Android AudioManager für Audio-Fokus Steuerung (Spotify pausieren) */
    private lateinit var audioManagerSystem: AudioManager

    /** Audio-Fokus Anfrage – wird gehalten solange der Jingle spielt */
    private var audioFocusRequest: AudioFocusRequest? = null

    /** Aktuelle Spielnummer (startet bei 1) */
    private var intSpielNummer = 1

    /** Startzeit des ersten Spiels in Millisekunden */
    private var longMsErsterStart: Long = 0

    /** Länge eines Zeitslots in Millisekunden */
    private var longMsZeitslot: Long = 0

    /** Zeitpunkt des Letzte-x-Minuten-Jingles innerhalb eines Slots in Millisekunden */
    private var longMsLetzteMin: Long = 0

    /** Lautstärke des Start-Jingles zwischen 0.0 und 1.0 */
    private var floatVolStartJingle: Float = 0.8f

    /** Software-Gain für alle drei Jingles zwischen 0.0 und 1.0 */
    private var floatVolJingle: Float = 0.8f

    /** Zeitpunkt des Schluss-Jingles innerhalb eines Slots in Millisekunden */
    private var longMsSchluss: Long = 0

    // ─── Lifecycle ───────────────────────────────────────────────────────────────

    /** Service hat keinen gebundenen Modus – nur als Foreground Service genutzt */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManagerSystem = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        erstelleNotificationChannel()
    }

    /**
     * Wird aufgerufen wenn der Service gestartet oder ein Intent empfangen wird.
     * Verarbeitet zwei Fälle:
     * 1. ACTION_LAUTSTAERKE → nur Lautstärke aktualisieren
     * 2. Normaler Start → Timer planen und sofort Status senden
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // Android hat den Service nach einem Kill neu gestartet (intent == null bei START_STICKY).
        // Parameter aus SharedPreferences wiederherstellen und Timer neu planen.
        if (intent == null) {
            val prefs = getSharedPreferences("TimerServiceState", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("S_LAEUFT", false)) return START_NOT_STICKY
            val startJingle  = prefs.getString("S_START_JINGLE",  null) ?: return START_NOT_STICKY
            val letzteMinJingle    = prefs.getString("S_END_JINGLE",    null) ?: return START_NOT_STICKY
            val schlussJingle = prefs.getString("S_SCHLUSS_JINGLE", null) ?: return START_NOT_STICKY
            floatVolStartJingle = prefs.getFloat("S_VOL_START", 0.8f)
            floatVolJingle      = prefs.getFloat("S_VOL_JINGLE", 0.8f)
            startForeground(NOTIF_ID, erstelleNotification("⏳ Timer läuft – warte auf Startzeit..."))
            planenAlle(
                prefs.getInt("S_HOUR", 0), prefs.getInt("S_MIN", 0),
                prefs.getInt("S_SLOT", 15), prefs.getInt("S_END_MIN", 12),
                prefs.getInt("S_SCHLUSS_MIN", 14),
                startJingle, letzteMinJingle, schlussJingle
            )
            sendeInitialenStatus()
            return START_STICKY
        }

        // Sonderfall: MainActivity ist wieder im Vordergrund und fragt nach aktuellem Status
        if (intent.action == ACTION_STATUS_ANFRAGEN) {
            if (longMsZeitslot > 0) sendeInitialenStatus()
            return START_STICKY
        }
        if (intent.action == ACTION_LAUTSTAERKE) {
            floatVolStartJingle = intent.getFloatExtra(EXTRA_LAUTSTAERKE_WERT, 0.8f)
            // Nur updaten wenn gerade ein Start-Jingle spielt (kein einfacher Weg zu unterscheiden →
            // setVolume schadet auch beim Letzte-x-Minuten-Jingle nicht gravierend, da Live-Update selten)
            mediaPlayerJingle?.setVolume(floatVolStartJingle, floatVolStartJingle)
            return START_STICKY
        }

        // Sonderfall: Jingle-Lautstärke aktualisieren (gilt für alle drei Jingles)
        if (intent.action == ACTION_LAUTSTAERKE_JINGLE) {
            floatVolJingle = intent.getFloatExtra(EXTRA_LAUTSTAERKE_JINGLE_WERT, 0.8f)
            mediaPlayerJingle?.setVolume(floatVolJingle, floatVolJingle)
            return START_STICKY
        }

        // Sonderfall: Test-Jingle über Service abspielen (BT-kompatibel, gleicher Code-Pfad)
        if (intent.action == ACTION_TEST_JINGLE) {
            val jingleTyp = intent.getStringExtra("JINGLE_TYP") ?: "START"
            val vol = if (jingleTyp == "START") floatVolStartJingle else floatVolJingle
            val uriString = intent.getStringExtra(EXTRA_TEST_JINGLE_URI) ?: return START_STICKY

            if (mediaPlayerJingle?.isPlaying == true) {
                mediaPlayerJingle?.stop()
                mediaPlayerJingle?.release()
                mediaPlayerJingle = null
                spotifyFortsetzten()
                sendBroadcast(Intent(BROADCAST_TEST_DONE).apply { setPackage(packageName) })
                return START_STICKY
            }

            val origListener = MediaPlayer.OnCompletionListener { mp ->
                spotifyFortsetzten()
                mp.release()
                mediaPlayerJingle = null
                sendBroadcast(Intent(BROADCAST_TEST_DONE).apply { setPackage(packageName) })
            }
            jingleSpielen(Uri.parse(uriString), volumeFactor = vol, onDone = origListener)
            return START_STICKY
        }

        // Normaler Start: Parameter aus dem Intent auslesen
        val intHourStart = intent.getIntExtra("START_STUNDE", 0)
        val intMinStart = intent.getIntExtra("START_MINUTE", 0)
        val intMinZeitSlot = intent.getIntExtra("ZEITSLOT", 15)
        val letzteMinMinuten = intent.getIntExtra("LETZTE_MIN_MINUTEN", 12)
        val schlussMinuten = intent.getIntExtra("SCHLUSS_MINUTEN", 14)
        val startJingle = intent.getStringExtra("START_JINGLE") ?: return START_NOT_STICKY
        val letzteMinJingle = intent.getStringExtra("LETZTE_MIN_JINGLE") ?: return START_NOT_STICKY
        val schlussJingle = intent.getStringExtra("SCHLUSS_JINGLE") ?: return START_NOT_STICKY
        floatVolStartJingle = intent.getFloatExtra("LAUTSTAERKE", 0.8f)
        floatVolJingle = intent.getFloatExtra("LAUTSTAERKE_JINGLE", 0.8f)

        speichereServiceParams(intHourStart, intMinStart, intMinZeitSlot, letzteMinMinuten, schlussMinuten, startJingle, letzteMinJingle, schlussJingle)

        startForeground(NOTIF_ID, erstelleNotification("⏳ Timer läuft – warte auf Startzeit..."))

        planenAlle(intHourStart, intMinStart, intMinZeitSlot, letzteMinMinuten, schlussMinuten, startJingle, letzteMinJingle, schlussJingle)

        // Sofortigen Status an die App senden (damit UI direkt nach Start befüllt ist)
        sendeInitialenStatus()

        return START_STICKY

    }

        /**
         * Speichert alle Timer-Parameter in SharedPreferences.
         * Wird beim normalen Start aufgerufen damit der Service nach einem Android-Kill
         * mit denselben Parametern wiederhergestellt werden kann (intent == null).
         */
        private fun speichereServiceParams(
            intHourStart: Int, intMinStart: Int,
            intMinZeitSlot: Int, letzteMinMinuten: Int, schlussMinuten: Int,
            startJingle: String, letzteMinJingle: String, schlussJingle: String
        ) {
            getSharedPreferences("TimerServiceState", Context.MODE_PRIVATE).edit().apply {
                putInt("S_HOUR", intHourStart)
                putInt("S_MIN", intMinStart)
                putInt("S_SLOT", intMinZeitSlot)
                putInt("S_END_MIN", letzteMinMinuten)
                putInt("S_SCHLUSS_MIN", schlussMinuten)
                putString("S_START_JINGLE", startJingle)
                putString("S_END_JINGLE", letzteMinJingle)
                putString("S_SCHLUSS_JINGLE", schlussJingle)
                putFloat("S_VOL_START", floatVolStartJingle)
                putFloat("S_VOL_JINGLE", floatVolJingle)
                putBoolean("S_LAEUFT", true)
                apply()
            }
        }

        /**
         * Löscht den gespeicherten Service-Zustand wenn der Timer bewusst gestoppt wird.
         */
        private fun loescheServiceParams() {
            getSharedPreferences("TimerServiceState", Context.MODE_PRIVATE).edit()
                .putBoolean("S_LAEUFT", false).apply()
        }

        override fun onDestroy() {
            loescheServiceParams()
            timerSpiele?.cancel()
            mediaPlayerJingle?.release()
            audioFocusRequest?.let { audioManagerSystem.abandonAudioFocusRequest(it) }
            super.onDestroy()
        }

        // ─── Timer Planung ───────────────────────────────────────────────────────────

        /**
         * Plant alle Timer-Tasks für Start- und Letzte-x-Minuten-Jingles.
         * Berechnet den korrekten Einstiegspunkt wenn die Startzeit in der Vergangenheit liegt.
         *
         * @param intHourStart Stunde des ersten Spielstarts
         * @param intMinStart Minute des ersten Spielstarts
         * @param intMinZeitSlot Länge eines Slots inkl. Pause in Minuten
         * @param letzteMinMinuten Minuten nach Spielbeginn wann Letzte-x-Minuten-Jingle gespielt wird
         * @param startJingle URI des Start-Jingles als String
         * @param letzteMinJingle URI des Letzte-x-Minuten-Jingles als String
         */
        @SuppressLint("DiscouragedApi")
        private fun planenAlle(
            intHourStart: Int, intMinStart: Int,
            intMinZeitSlot: Int, letzteMinMinuten: Int, schlussMinuten: Int,
            startJingle: String, letzteMinJingle: String, schlussJingle: String
        ) {
            timerSpiele = Timer()

            // Zeitslot und Jingle-Offsets in Millisekunden umrechnen
            longMsZeitslot = intMinZeitSlot * 60 * 1000L
            longMsLetzteMin = letzteMinMinuten * 60 * 1000L
            longMsSchluss = schlussMinuten * 60 * 1000L

            // Startzeit auf heute setzen (kein automatischer Sprung auf morgen)
            val ersterStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, intHourStart)
                set(Calendar.MINUTE, intMinStart)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            longMsErsterStart = ersterStart.timeInMillis

            val jetzt = Calendar.getInstance()
            val verstrichenMs = jetzt.timeInMillis - ersterStart.timeInMillis

            // Berechnen wie viele Slots seit dem ersten Start vergangen sind
            val aktuellesSpielOffset = if (verstrichenMs > 0) (verstrichenMs / longMsZeitslot).toInt() else 0

            // Spielnummer: mindestens 1, auch wenn Startzeit in der Zukunft liegt
            intSpielNummer = if (verstrichenMs > 0) aktuellesSpielOffset + 1 else 1

            // Nächsten Start-Jingle finden der in der Zukunft liegt
            val naechsterStartMs = ersterStart.timeInMillis + (intSpielNummer - 1) * longMsZeitslot
            val naechsterStartZeit = Calendar.getInstance().apply {
                timeInMillis = if (naechsterStartMs > jetzt.timeInMillis) naechsterStartMs
                else naechsterStartMs + longMsZeitslot
            }

            // Letzte-x-Minuten-Jingle des aktuellen Slots berechnen
            val aktuellerSpielbeginnMs = ersterStart.timeInMillis + aktuellesSpielOffset * longMsZeitslot
            val naechsterEndZeit = Calendar.getInstance().apply {
                val endMs_aktuell = aktuellerSpielbeginnMs + longMsLetzteMin
                timeInMillis = if (endMs_aktuell > jetzt.timeInMillis) endMs_aktuell
                else aktuellerSpielbeginnMs + longMsZeitslot + longMsLetzteMin
            }

            Log.d("TurnierTimer", "Aktuelles Spiel: $intSpielNummer")
            Log.d("TurnierTimer", "Nächster Start-Jingle: ${naechsterStartZeit.time}")
            Log.d("TurnierTimer", "Nächster Letzte-x-Minuten-Jingle: ${naechsterEndZeit.time}")

            // Start-Jingle: wiederholt sich alle `longMsZeitslot` Millisekunden
            timerSpiele?.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val aktuellesSpiel = intSpielNummer + 1

                    // Nächsten Spielbeginn für die Anzeige berechnen
                    val aktuellesMs = ersterStart.timeInMillis + intSpielNummer * longMsZeitslot
                    val aktuellesUhrzeit = kalenderZuUhrzeit(aktuellesMs)
                    val naechsterMs = ersterStart.timeInMillis + aktuellesSpiel * longMsZeitslot
                    val naechsteUhrzeit = kalenderZuUhrzeit(naechsterMs)

                    // Letzte-x-Minuten-Jingle Uhrzeit für diesen Slot
                    val letzteMinJingleMs = ersterStart.timeInMillis + (aktuellesSpiel - 1) * longMsZeitslot + longMsLetzteMin
                    val letzteMinJingleUhrzeit = kalenderZuUhrzeit(letzteMinJingleMs)

                    val schlussJingleMs2 = ersterStart.timeInMillis + (aktuellesSpiel - 1) * longMsZeitslot + longMsSchluss
                    val schlussJingleUhrzeit2 = kalenderZuUhrzeit(schlussJingleMs2)

                    aktualisiereNotification("🟢 Spiel $aktuellesSpiel läuft – endet um $naechsteUhrzeit Uhr")
                    sendeUpdate(
                        stringSpielNr = "🟢 Spiel $aktuellesSpiel um $aktuellesUhrzeit Uhr gestartet!",
                        stringNaechstes = "🕐 Nächstes Spiel um $naechsteUhrzeit Uhr",
                        stringLetzteMinJingleInfo = "⏰ Letzte-x-Minuten-Jingle um $letzteMinJingleUhrzeit Uhr",
                        stringSchlussJingleInfo = "🏁 Schluss-Jingle um $schlussJingleUhrzeit2 Uhr"
                    )

                    jingleSpielen(Uri.parse(startJingle), volumeFactor = floatVolStartJingle)
                    intSpielNummer++ // Spielnummer für nächsten Durchlauf erhöhen
                }
            }, naechsterStartZeit.time, longMsZeitslot)

            // Letzte-x-Minuten-Jingle: ebenfalls alle `longMsZeitslot` Millisekunden, aber versetzt
            timerSpiele?.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val aktuellesSpiel = intSpielNummer

                    val naechsterMs = ersterStart.timeInMillis + aktuellesSpiel * longMsZeitslot
                    val naechsteUhrzeit = kalenderZuUhrzeit(naechsterMs)

                    // Schluss-Jingle Uhrzeit für diesen Slot
                    val schlussJingleMs = ersterStart.timeInMillis + (aktuellesSpiel - 1) * longMsZeitslot + longMsSchluss
                    val schlussJingleUhrzeit = kalenderZuUhrzeit(schlussJingleMs)

                    aktualisiereNotification("⏰ Spiel $aktuellesSpiel endet bald – nächstes startet um $naechsteUhrzeit Uhr")
                    sendeUpdate(
                        stringSpielNr = "⏰ Spiel $aktuellesSpiel endet bald!",
                        stringNaechstes = "🕐 Nächstes Spiel um $naechsteUhrzeit Uhr",
                        stringLetzteMinJingleInfo = "",
                        stringSchlussJingleInfo = "🏁 Schluss-Jingle um $schlussJingleUhrzeit Uhr"
                    )

                    jingleSpielen(Uri.parse(letzteMinJingle), volumeFactor = floatVolJingle)
                }
            }, naechsterEndZeit.time, longMsZeitslot)

            // Schluss-Jingle: gleiche Periode, zwischen End- und Start-Jingle
            val naechsterSchlussZeit = Calendar.getInstance().apply {
                val schlussMs_aktuell = aktuellerSpielbeginnMs + longMsSchluss
                timeInMillis = if (schlussMs_aktuell > jetzt.timeInMillis) schlussMs_aktuell
                else aktuellerSpielbeginnMs + longMsZeitslot + longMsSchluss
            }
            Log.d("TurnierTimer", "Nächster Schluss-Jingle: ${naechsterSchlussZeit.time}")

            timerSpiele?.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val aktuellesSpiel = intSpielNummer

                    val naechsterMs = ersterStart.timeInMillis + aktuellesSpiel * longMsZeitslot
                    val naechsteUhrzeit = kalenderZuUhrzeit(naechsterMs)

                    aktualisiereNotification("🏁 Spiel $aktuellesSpiel beendet – Spiel ${aktuellesSpiel + 1} startet um $naechsteUhrzeit Uhr")
                    sendeUpdate(
                        stringSpielNr = "🏁 Spiel $aktuellesSpiel ist beendet!",
                        stringNaechstes = "🕐 Nächstes Spiel um $naechsteUhrzeit Uhr",
                        stringLetzteMinJingleInfo = "",
                        stringSchlussJingleInfo = ""
                    )

                    jingleSpielen(Uri.parse(schlussJingle), volumeFactor = floatVolJingle)
                }
            }, naechsterSchlussZeit.time, longMsZeitslot)
        }

        /**
         * Sendet sofort nach dem Timer-Start den aktuellen Status an die App.
         * So sind die Felder direkt nach "Timer starten" befüllt.
         */
        private fun sendeInitialenStatus() {
            val jetztMs = Calendar.getInstance().timeInMillis

            // Nächsten Start-Jingle finden der strikt in der Zukunft liegt
            var naechsterStartMs = longMsErsterStart
            while (naechsterStartMs <= jetztMs) {
                naechsterStartMs += longMsZeitslot
            }

            // Letzter Spielbeginn = aktuell laufendes Spiel
            val letzterStartMs = naechsterStartMs - longMsZeitslot

            // Letzte-x-Minuten-Jingle: vom aktuellen Spiel wenn noch nicht vorbei, sonst vom nächsten
            val letzteMinJingleImAktuellenSpielMs = if (jetztMs < longMsErsterStart) {
                longMsErsterStart + longMsLetzteMin  // Noch vor dem ersten Spiel → Letzte-x-Minuten-Jingle von Spiel 1
            } else {
                letzterStartMs + longMsLetzteMin // Spiel läuft bereits
            }

            val anzeigenLetzteMinJingleMs = if (letzteMinJingleImAktuellenSpielMs > jetztMs) {
                letzteMinJingleImAktuellenSpielMs
            } else {
                naechsterStartMs + longMsLetzteMin
            }

            // Schluss-Jingle: ähnliche Logik wie Letzte-x-Minuten-Jingle
            val schlussJingleImAktuellenSpielMs = if (jetztMs < longMsErsterStart) {
                longMsErsterStart + longMsSchluss
            } else {
                letzterStartMs + longMsSchluss
            }
            val anzeigenSchlussJingleMs = if (schlussJingleImAktuellenSpielMs > jetztMs) {
                schlussJingleImAktuellenSpielMs
            } else {
                naechsterStartMs + longMsSchluss
            }

            // Aktuelle Spielnummer berechnen (mindestens 1)
            val aktuellesSpiel = maxOf(1, ((letzterStartMs - longMsErsterStart) / longMsZeitslot).toInt() + 1)

            val notifText = if (jetztMs < longMsErsterStart) {
                "⏳ Spiel 1 startet um ${kalenderZuUhrzeit(longMsErsterStart)} Uhr"
            } else {
                "🟢 Spiel $aktuellesSpiel läuft – endet um ${kalenderZuUhrzeit(naechsterStartMs)} Uhr"
            }
            aktualisiereNotification(notifText)

            sendeUpdate(
                stringSpielNr = "🟢 Aktuelles Spiel: $aktuellesSpiel",
                stringNaechstes = "🕐 Nächster Start-Jingle: ${kalenderZuUhrzeit(naechsterStartMs)} Uhr",
                stringLetzteMinJingleInfo = "⏰ Letzte-x-Minuten-Jingle um ${kalenderZuUhrzeit(anzeigenLetzteMinJingleMs)} Uhr",
                stringSchlussJingleInfo = "🏁 Schluss-Jingle um ${kalenderZuUhrzeit(anzeigenSchlussJingleMs)} Uhr"
            )
        }

        // ─── Audio ───────────────────────────────────────────────────────────────────

        /**
         * Spielt einen Jingle ab.
         * Pausiert zuerst Spotify via Audio-Fokus, spielt dann den Jingle,
         * und gibt den Audio-Fokus danach wieder frei (Spotify läuft weiter).
         *
         * @param uri URI der abzuspielenden Audiodatei
         * @param volumeFactor Lautstärke-Faktor zwischen 0.0 und 1.0 (default = Start-Jingle-Lautstärke)
         * @param onDone Optionaler Callback nach Wiedergabe-Ende (z. B. für Test-Button-Reset)
         */
        private fun jingleSpielen(uri: Uri, volumeFactor: Float = floatVolStartJingle, onDone: MediaPlayer.OnCompletionListener? = null) {
            Log.d("TurnierTimer", "jingleSpielen aufgerufen: $uri")

            spotifyPausieren() // Audio-Fokus anfordern → Spotify pausiert

            Thread.sleep(500) // Kurz warten damit Spotify sicher pausiert ist

            try {
                mediaPlayerJingle?.release() // Alten Player freigeben falls noch aktiv

                mediaPlayerJingle = MediaPlayer().apply {
                    // Medien-Stream verwenden
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            //.setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )

                    setDataSource(applicationContext, uri)

                    prepare()
                    start()
                    setVolume(volumeFactor, volumeFactor) // Individuell eingestellte Lautstärke anwenden

                    Log.d("TurnierTimer", "Jingle spielt! Lautstärke: $volumeFactor")

                    // Nach Ende Audio-Fokus freigeben → Spotify läuft weiter
                    setOnCompletionListener { mp ->
                        if (onDone != null) {
                            onDone.onCompletion(mp)
                        } else {
                            spotifyFortsetzten()
                            mp.release()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TurnierTimer", "Fehler beim Abspielen: ${e.message}")
                spotifyFortsetzten() // Auch bei Fehler Spotify wieder starten
            }
        }

        /**
         * Fordert den Audio-Fokus an.
         * Android signalisiert dadurch anderen Apps (Spotify) zu pausieren.
         * AUDIOFOCUS_GAIN_TRANSIENT = temporär, andere App soll danach weiterspielen.
         */
        private fun spotifyPausieren() {
            val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        //.setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioManagerSystem.requestAudioFocus(audioFocusRequest!!)
        }

        /**
         * Gibt den Audio-Fokus wieder frei.
         * Spotify und andere Apps erhalten dadurch den Fokus zurück und spielen weiter.
         */
        private fun spotifyFortsetzten() {
            audioFocusRequest?.let {
                audioManagerSystem.abandonAudioFocusRequest(it)
            }
        }

        // ─── Notifications ───────────────────────────────────────────────────────────

        /**
         * Erstellt den Notification-Channel (einmalig nötig, ab Android 8).
         * IMPORTANCE_HIGH damit die Benachrichtigung auch mit Ton erscheint.
         */
        private fun erstelleNotificationChannel() {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Turnier Timer",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Turnier Timer Benachrichtigungen"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /**
         * Erstellt eine Notification mit dem angegebenen Text.
         * Tippen auf die Notification öffnet die MainActivity.
         *
         * @param text Anzuzeigender Text in der Notification
         */
        private fun erstelleNotification(text: String): Notification {
            val pendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Turnier Timer")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setOngoing(true) // Kann nicht vom Nutzer weggewischt werden
                .build()
        }

        /**
         * Aktualisiert den Text der dauerhaften Foreground-Notification.
         * Muss im Main-Thread ausgeführt werden.
         *
         * @param text Neuer Anzeigetext
         */
        private fun aktualisiereNotification(text: String) {
            android.os.Handler(mainLooper).post {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, erstelleNotification(text))
            }
        }

        // ─── Hilfsmethoden ───────────────────────────────────────────────────────────

        /**
         * Sendet einen Broadcast mit dem aktuellen Spielstatus an die MainActivity.
         *
         * @param stringSpielNr Text für die Spielnummer-Zeile
         * @param stringNaechstes Text für die nächste Spielzeit
         * @param stringLetzteMinJingleInfo Text für die Letzte-x-Minuten-Jingle Uhrzeit
         */
        private fun sendeUpdate(
            stringSpielNr: String,
            stringNaechstes: String,
            stringLetzteMinJingleInfo: String,
            stringSchlussJingleInfo: String = ""
        ) {
            val intent = Intent(BROADCAST_ACTION).apply {
                putExtra(EXTRA_SPIEL_NR, stringSpielNr)
                putExtra(EXTRA_NAECHSTES, stringNaechstes)
                putExtra(EXTRA_LETZTE_MIN_JINGLE, stringLetzteMinJingleInfo)
                putExtra(EXTRA_SCHLUSS_JINGLE, stringSchlussJingleInfo)
                putExtra(EXTRA_TIMER_LAEUFT, longMsZeitslot > 0)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        /**
         * Wandelt einen Zeitstempel in einen lesbaren Uhrzeitstring um.
         *
         * @param ms Zeitstempel in Millisekunden
         * @return Uhrzeit als String im Format "HH:MM"
         */
        private fun kalenderZuUhrzeit(ms: Long): String {
            val calJetzt = Calendar.getInstance().apply { timeInMillis = ms }
            return "%02d:%02d".format(
                calJetzt.get(Calendar.HOUR_OF_DAY),
                calJetzt.get(Calendar.MINUTE)
            )
        }
    }