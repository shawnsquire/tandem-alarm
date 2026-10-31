package me.shawnsquire.tandem.alarm

/** What the audio adapter should do after an event. */
internal sealed interface SoundDecision {
    /** Create a player for [uri]; events for it must carry [generation]. */
    data class Try(val uri: String, val generation: Int) : SoundDecision

    /** The current attempt is prepared: start playback. */
    data object Play : SoundDecision

    /** Stale, stopped or irrelevant event: do nothing. */
    data object Ignore : SoundDecision

    /** Every candidate, including the bundled tone, failed. */
    data object Exhausted : SoundDecision
}

/**
 * Pure decision logic for the alarm sound chain. Not thread-safe: the adapter drives it from one
 * thread. Each attempt gets a new generation, so a callback from a released player is ignored.
 * [bundledUri] is always the last candidate, so the chain ends on a tone that cannot be "None".
 */
internal class SoundSequencer(candidates: List<String>, bundledUri: String) {
    private val chain: List<String> = candidates.filter { it != bundledUri } + bundledUri
    private var index = -1
    private var generation = 0
    private var playing = false
    private var stopped = false
    private var exhausted = false

    fun begin(): SoundDecision = advance()

    fun onPrepared(generation: Int): SoundDecision {
        if (!current(generation) || playing) return SoundDecision.Ignore
        playing = true
        return SoundDecision.Play
    }

    /** Prepare or mid-ring playback error: move to the next candidate. */
    fun onError(generation: Int): SoundDecision =
        if (current(generation)) advance() else SoundDecision.Ignore

    /**
     * Not prepared in time: move on, except for the last candidate, which is left preparing since
     * nothing could replace it.
     */
    fun onTimeout(generation: Int): SoundDecision {
        if (!current(generation) || playing || index >= chain.lastIndex) return SoundDecision.Ignore
        return advance()
    }

    fun stop() {
        stopped = true
        generation++
    }

    private fun current(g: Int) = !stopped && !exhausted && g == generation

    private fun advance(): SoundDecision {
        if (stopped) return SoundDecision.Ignore
        playing = false
        generation++
        index++
        if (index > chain.lastIndex) {
            exhausted = true
            return SoundDecision.Exhausted
        }
        return SoundDecision.Try(chain[index], generation)
    }
}
