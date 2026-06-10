package com.ultiorga.turniertimer

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class TimerBerechnungTest {

    // Hilfsfunktion: Uhrzeit als Millisekunden heute
    private fun uhrzeitHeute(stunde: Int, minute: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, stunde)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    // Hilfsfunktion: gleiche Berechnung wie im Service
    private fun berechneAnzeige(
        longMsErsterStart: Long,
        longMsZeitslot: Long,
        longMsEnd: Long,
        jetztMs: Long
    ): Triple<Int, Long, Long> {

        // Nächsten Start-Jingle finden
        var naechsterStartMs = longMsErsterStart
        while (naechsterStartMs <= jetztMs) {
            naechsterStartMs += longMsZeitslot
        }

        // Letzter Start = laufendes Spiel
        val letzterStartMs = naechsterStartMs - longMsZeitslot

        // Falls jetzt vor erstem Start → End-Jingle vom ersten Spiel
        val endJingleImAktuellenSpielMs = if (jetztMs < longMsErsterStart) {
            longMsErsterStart + longMsEnd
        } else {
            letzterStartMs + longMsEnd
        }

        val anzeigenEndJingleMs = if (endJingleImAktuellenSpielMs > jetztMs) {
            endJingleImAktuellenSpielMs
        } else {
            naechsterStartMs + longMsEnd
        }

        // Spiel 1 als Minimum
        val aktuellesSpiel = maxOf(1, ((letzterStartMs - longMsErsterStart) / longMsZeitslot).toInt() + 1)

        return Triple(aktuellesSpiel, naechsterStartMs, anzeigenEndJingleMs)
    }

    private fun formatZeit(ms: Long): String {
        val calJetzt = Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d:%02d".format(calJetzt.get(Calendar.HOUR_OF_DAY), calJetzt.get(Calendar.MINUTE))
    }

    @Test
    fun `spiel 1 noch nicht gestartet`() {
        val ersterStart = uhrzeitHeute(10, 0)
        val longMsZeitslot = 30 * 60 * 1000L
        val longMsEnd = 25 * 60 * 1000L
        val jetzt = uhrzeitHeute(9, 50) // 10 min vor Start

        val (spiel, naechsterStart, endJingle) = berechneAnzeige(ersterStart, longMsZeitslot, longMsEnd, jetzt)

        println("Spiel: $spiel | NächsterStart: ${formatZeit(naechsterStart)} | EndJingle: ${formatZeit(endJingle)}")

        assertEquals(1, spiel)
        assertEquals("10:00", formatZeit(naechsterStart))
        assertEquals("10:25", formatZeit(endJingle))
    }

    @Test
    fun `spiel 1 laeuft end jingle noch nicht gespielt`() {
        val ersterStart = uhrzeitHeute(10, 0)
        val longMsZeitslot = 30 * 60 * 1000L
        val longMsEnd = 25 * 60 * 1000L
        val jetzt = uhrzeitHeute(10, 10) // 10 min nach Start

        val (spiel, naechsterStart, endJingle) = berechneAnzeige(ersterStart, longMsZeitslot, longMsEnd, jetzt)

        println("Spiel: $spiel | NächsterStart: ${formatZeit(naechsterStart)} | EndJingle: ${formatZeit(endJingle)}")

        assertEquals(1, spiel)
        assertEquals("10:30", formatZeit(naechsterStart))
        assertEquals("10:25", formatZeit(endJingle))
    }

    @Test
    fun `spiel 1 laeuft end jingle bereits gespielt`() {
        val ersterStart = uhrzeitHeute(10, 0)
        val longMsZeitslot = 30 * 60 * 1000L
        val longMsEnd = 25 * 60 * 1000L
        val jetzt = uhrzeitHeute(10, 27) // End-Jingle bereits vorbei

        val (spiel, naechsterStart, endJingle) = berechneAnzeige(ersterStart, longMsZeitslot, longMsEnd, jetzt)

        println("Spiel: $spiel | NächsterStart: ${formatZeit(naechsterStart)} | EndJingle: ${formatZeit(endJingle)}")

        assertEquals(1, spiel)
        assertEquals("10:30", formatZeit(naechsterStart))
        assertEquals("10:55", formatZeit(endJingle)) // End-Jingle vom nächsten Spiel
    }

    @Test
    fun `spiel 5 laeuft`() {
        val ersterStart = uhrzeitHeute(10, 0)
        val longMsZeitslot = 30 * 60 * 1000L
        val longMsEnd = 25 * 60 * 1000L
        val jetzt = uhrzeitHeute(12, 10) // Spiel 5 läuft (10:00 + 4*30 = 12:00)

        val (spiel, naechsterStart, endJingle) = berechneAnzeige(ersterStart, longMsZeitslot, longMsEnd, jetzt)

        println("Spiel: $spiel | NächsterStart: ${formatZeit(naechsterStart)} | EndJingle: ${formatZeit(endJingle)}")

        assertEquals(5, spiel)
        assertEquals("12:30", formatZeit(naechsterStart))
        assertEquals("12:25", formatZeit(endJingle))
    }

    @Test
    fun `dein fall 23h30 start 23h40 jetzt`() {
        val ersterStart = uhrzeitHeute(23, 30)
        val longMsZeitslot = 30 * 60 * 1000L
        val longMsEnd = 25 * 60 * 1000L
        val jetzt = uhrzeitHeute(23, 40)

        val (spiel, naechsterStart, endJingle) = berechneAnzeige(ersterStart, longMsZeitslot, longMsEnd, jetzt)

        println("Spiel: $spiel | NächsterStart: ${formatZeit(naechsterStart)} | EndJingle: ${formatZeit(endJingle)}")

        assertEquals(1, spiel)
        assertEquals("00:00", formatZeit(naechsterStart))
        assertEquals("23:55", formatZeit(endJingle))
    }
}