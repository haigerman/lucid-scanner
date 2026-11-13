package io.github.haigerman.lucidscanner.scan

enum class Phase {
    /** Kein Blatt im Bild. */
    SEARCHING,

    /** Blatt ragt über den Bildrand. */
    PARTIAL,

    /** Blatt erkannt, Haltezeit läuft. */
    HOLD,

    /** Aufnahme ausgelöst, Ergebnis steht noch aus. */
    CAPTURING,

    /** Die zuletzt erfasste Seite liegt noch da – warten auf das nächste Blatt. */
    WAIT_NEXT,
}

class TriggerState(val phase: Phase, val progress: Float, val fire: Boolean)

/**
 * Entscheidet, wann automatisch ausgelöst wird.
 *
 * Ausgelöst wird, wenn das Blatt [holdMs] lang ruhig im Bild liegt. Nach einer Aufnahme wird erst
 * wieder ausgelöst, wenn sich etwas getan hat: Das Blatt war kurz weg, oder sein Inhalt hat sich
 * sichtbar geändert (Hand im Bild, neue Seite). So wird dieselbe Seite nicht mehrfach erfasst.
 */
class CaptureTrigger(
    private val holdMs: Long = 650,
    private val moveTolerance: Float = 0.02f,
    private val lostMs: Long = 250,
    private val changeTolerance: Float = 4.5f,
) {
    private var anchor: Quad? = null
    private var stableSince = 0L
    private var busy = false

    private var lastSignature: FloatArray? = null
    private var changed = true
    private var lostSince = -1L
    private var changeFrames = 0

    fun onFrame(sighting: Sighting, quad: Quad?, signature: FloatArray?, nowMs: Long): TriggerState {
        if (busy) return TriggerState(Phase.CAPTURING, 1f, false)

        if (sighting != Sighting.FOUND || quad == null || signature == null) {
            anchor = null
            if (lostSince < 0) lostSince = nowMs
            if (nowMs - lostSince >= lostMs) changed = true
            return TriggerState(if (sighting == Sighting.PARTIAL) Phase.PARTIAL else Phase.SEARCHING, 0f, false)
        }
        lostSince = -1

        val last = lastSignature
        if (!changed && last != null) {
            // zwei Bilder in Folge, damit einzelnes Rauschen nicht reicht
            if (DocumentDetector.signatureDiff(signature, last) > changeTolerance) changeFrames++ else changeFrames = 0
            if (changeFrames >= 2) changed = true
        }
        if (!changed) {
            anchor = null
            return TriggerState(Phase.WAIT_NEXT, 0f, false)
        }

        val a = anchor
        if (a == null || a.maxCornerDistance(quad) > moveTolerance) {
            anchor = quad
            stableSince = nowMs
            return TriggerState(Phase.HOLD, 0f, false)
        }
        val progress = ((nowMs - stableSince).toFloat() / holdMs).coerceIn(0f, 1f)
        if (progress >= 1f) {
            busy = true
            return TriggerState(Phase.CAPTURING, 1f, true)
        }
        return TriggerState(Phase.HOLD, progress, false)
    }

    /** Vor einer Aufnahme von Hand: verhindert, dass währenddessen automatisch ausgelöst wird. */
    fun beginManual(): Boolean {
        if (busy) return false
        busy = true
        return true
    }

    /** Die Aufnahme ist im Kasten; [signature] ist der Fingerabdruck der erfassten Seite (falls erkannt). */
    fun onCaptured(signature: FloatArray?) {
        busy = false
        anchor = null
        lastSignature = signature
        changed = signature == null
        changeFrames = 0
        lostSince = -1
    }

    fun onCaptureFailed() {
        busy = false
        anchor = null
    }

    /** Letzte Seite wurde verworfen – sie darf sofort neu erfasst werden. */
    fun reset() {
        lastSignature = null
        changed = true
        anchor = null
        changeFrames = 0
    }
}
