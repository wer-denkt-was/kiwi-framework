package de.kiwi.api.application.service

import java.time.Clock

/**
 * Service providing an application-wide [Clock] instance to be used for timestamping.
 */
fun interface ClockProvider {
    /**
     * Get the current application clock, providing a canonical application timestamp
     */
    fun clock(): Clock

    /**
     * Default [ClockProvider] implementation, always returning [Clock.systemUTC]
     */
    object SystemUTC : ClockProvider {
        override fun clock(): Clock = Clock.systemUTC()
    }
}
