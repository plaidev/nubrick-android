package app.nubrick.nubrick.component

/** One experiment flow, independent of how its pages are presented. */
internal class TriggerExperimentSession {
    private enum class RecordingState { NOT_RECORDED, RECORDING, RECORDED }

    private var owner: String? = null
    private var running = false
    private var recording = RecordingState.NOT_RECORDED

    @Synchronized
    fun isActive(): Boolean = owner != null

    @Synchronized
    fun start(sessionId: String): Boolean {
        if (sessionId.isEmpty() || owner != null) return false
        owner = sessionId
        running = true
        recording = RecordingState.NOT_RECORDED
        return true
    }

    @Synchronized
    fun owns(sessionId: String): Boolean = owner == sessionId && running

    @Synchronized
    fun beginRecording(sessionId: String): Boolean {
        if (!owns(sessionId) || recording != RecordingState.NOT_RECORDED) return false
        recording = RecordingState.RECORDING
        return true
    }

    @Synchronized
    fun finishRecording(sessionId: String) {
        if (owner != sessionId || recording != RecordingState.RECORDING) return
        recording = RecordingState.RECORDED
        releaseIfFinished()
    }

    @Synchronized
    fun finish(sessionId: String) {
        if (owner != sessionId) return
        running = false
        releaseIfFinished()
    }

    private fun releaseIfFinished() {
        if (!running && recording != RecordingState.RECORDING) owner = null
    }
}
