package de.kiwi.api.domain

import de.kiwi.api.util.Base32Id
import de.kiwi.api.util.slf4j
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

private val log = slf4j<Snowflake>()

/**
 * Core service for generating **60-bit** unique identifier numbers,
 * based on [Twitter's Snowflake ID](https://en.wikipedia.org/wiki/Snowflake_ID) algorithm.
 *
 * The 60 bits, made to fit neatly into a 12-char Base32 ID, are composed of the following components:
 * - 42 bit timestamp in milliseconds, offset from 2024-01-01 00:00:00 UTC.
 * - 10 bit machine ID, based on system property, environment variable, or hashing the local hostname of the current runtime.
 * - 8 bit sequence ID, used in case multiple IDs are generated at the same millisecond instant.
 *
 * Represented as a [Long], the top 4 bits are padded as zero.
 * Bit format overview: `0000ttttttttttttttttttttttttttttttttttttttttttmmmmmmmmmmssssssss`.
 *
 * (`t` = Timestamp bit, `m` = Machine ID bit, `s` = Sequence ID bit)
 *
 * The 10 bit Machine ID (range 0-1023) is determined in the following order:
 * 1. System property `kiwi.snowflake.machine.id` (as Long)
 * 2. Environment variable `KIWI_SNOWFLAKE_MACHINE_ID` (as Long)
 * 3. Fallback: Hash of local hostname
 */
object Snowflake {
    const val TIMESTAMP_BITS = 42
    const val MACHINE_ID_BITS = 10
    const val SEQUENCE_ID_BITS = 8
    const val SNOWFLAKE_BITS = TIMESTAMP_BITS + MACHINE_ID_BITS + SEQUENCE_ID_BITS
    const val TIMESTAMP_MASK = (1L shl TIMESTAMP_BITS) - 1 // 0x3FFFFFFFFFF
    const val MACHINE_ID_MASK = (1L shl MACHINE_ID_BITS) - 1 // 0x3FF
    const val SEQUENCE_ID_MASK = (1L shl SEQUENCE_ID_BITS) - 1 // 0xFF
    const val TIMESTAMP_EPOCH = "2024-01-01T00:00:00"

    const val MACHINE_ID_SYSTEM_PROPERTY = "kiwi.snowflake.machine.id"
    const val MACHINE_ID_SYSTEM_ENV = "KIWI_SNOWFLAKE_MACHINE_ID"

    /**
     * The machine ID is determined in the following order:
     * 1. System property `kiwi.snowflake.machine.id` (as Long)
     * 2. Environment variable `KIWI_SNOWFLAKE_MACHINE_ID` (as Long)
     * 3. Fallback: Hash of local hostname
     */
    val machineId: Long = evaluateSystemMachineId()

    private val clock = Clock.systemUTC()
    private val timestampOffset = LocalDateTime.parse(TIMESTAMP_EPOCH).toInstant(ZoneOffset.UTC)
    private val base32Id = Base32Id(bits = SNOWFLAKE_BITS, reverse = false)

    private var sequenceCount = 0L
    private var lastTimestamp = 0L

    init {
        @Suppress("SimplifyBooleanWithConstants")
        assert(SNOWFLAKE_BITS < Long.SIZE_BITS) { "Snowflake is broken" }
    }

    /**
     * Generate a unique 60-bit snowflake ID.
     * This operation may be delayed for up to 1 millisecond if more than [SEQUENCE_ID_MASK] (255)
     * snowflake IDs are being generated at the same millisecond on this machine.
     */
    fun generate(): Long {
        val (timestamp, sequenceId) = synchronized(this) {
            uniqueTimestampAndSequenceId()
        }

        // Generate ID from timestamp, machine and sequence components
        return ((timestamp and TIMESTAMP_MASK) shl (SEQUENCE_ID_BITS + MACHINE_ID_BITS)) or
                ((machineId and MACHINE_ID_MASK) shl SEQUENCE_ID_BITS) or
                (sequenceId and SEQUENCE_ID_MASK)
    }

    /**
     * Encode a snowflake ID as a base32-string with 12 characters.
     * @see Base32Id
     */
    fun encode(value: Long) = base32Id.encode(value)

    /**
     * Decode a snowflake ID from a base-32 string with up to 12 characters.
     * This returns `null` if the encoded ID is not valid and can not be decoded.
     * @see Base32Id
     */
    fun decode(value: String) = if (base32Id.isValid(value)) base32Id.decode(value) else null

    /**
     * Extract the timestamp encoded into the given Snowflake value
     */
    fun extractTimestamp(value: Long): Instant {
        val millis = (value ushr (MACHINE_ID_BITS + SEQUENCE_ID_BITS)) and TIMESTAMP_MASK
        val offset = timestampOffset.toEpochMilli()
        return Instant.ofEpochMilli(offset + millis)
    }

    /**
     * Extract the Machine ID encoded into the given Snowflake value
     */
    fun extractMachineId(value: Long) = ((value ushr SEQUENCE_ID_BITS) and MACHINE_ID_MASK).toInt()

    /**
     * Extract the Sequence ID encoded into the given Snowflake value
     */
    fun extractSequenceId(value: Long) = (value and SEQUENCE_ID_MASK).toInt()

    private fun uniqueTimestampAndSequenceId(): TimestampAndSequenceId {
        val firstTimestamp = currentTimestamp()
        if (lastTimestamp == firstTimestamp) {
            sequenceCount++
            if (sequenceCount > SEQUENCE_ID_MASK) {
                sequenceCount = 0

                // Force system to wait until the next millisecond is reached,
                // resulting in a maximum of 256 IDs generated per millisecond.
                var nextTimestamp = currentTimestamp()
                while (firstTimestamp == nextTimestamp) {
                    nextTimestamp = currentTimestamp()
                }
                lastTimestamp = nextTimestamp
                return TimestampAndSequenceId(nextTimestamp, sequenceCount)
            }
        } else {
            sequenceCount = 0
        }
        lastTimestamp = firstTimestamp
        return TimestampAndSequenceId(firstTimestamp, sequenceCount)
    }

    private fun currentTimestamp(): Long {
        val now = Instant.now(clock).toEpochMilli()
        val offset = timestampOffset.toEpochMilli()
        return now - offset
    }

    private fun evaluateSystemMachineId(): Long {
        val machineIdSystemPropDescription = """System.getProperty("$MACHINE_ID_SYSTEM_PROPERTY")"""
        System.getProperty(MACHINE_ID_SYSTEM_PROPERTY)
            ?.parseValidMachineId(machineIdSystemPropDescription)
            ?.let { systemPropMachineId ->
                log.info("Using Snowflake Machine ID [$systemPropMachineId], from $machineIdSystemPropDescription")
                return systemPropMachineId
            }

        val machineIdSystemEnvDescription = """System.getenv("$MACHINE_ID_SYSTEM_ENV")"""
        System.getenv(MACHINE_ID_SYSTEM_ENV)
            ?.parseValidMachineId(machineIdSystemEnvDescription)
            ?.let { systemEnvMachineId ->
                log.info("Using Snowflake Machine ID [$systemEnvMachineId], from $machineIdSystemEnvDescription")
                return systemEnvMachineId
            }

        val hostName = InetAddress.getLocalHost().hostName
        val hostNameMachineId = hostName.hashCode().toLong() and MACHINE_ID_MASK
        log.info("""Using Snowflake Machine ID [$hostNameMachineId], from hashCode of hostname "$hostName"""")
        log.debug("Use $machineIdSystemPropDescription or $machineIdSystemEnvDescription to supply a custom Snowflake Machine ID")
        return hostNameMachineId
    }

    private fun String.parseValidMachineId(description: String): Long? {
        val rawValue = toLongOrNull()
            ?: throw AssertionError("Invalid Snowflake Machine ID: $description was \"$this\", not a valid integer")

        val maskedValue = rawValue and MACHINE_ID_MASK

        if (rawValue != maskedValue) {
            throw AssertionError("Invalid Snowflake Machine ID: $description was [$rawValue], but got truncated to [$maskedValue] (max allowed is [$MACHINE_ID_MASK])")
        }

        return maskedValue
    }
}

private data class TimestampAndSequenceId(
    val timestamp: Long,
    val sequenceId: Long,
)
