package de.kiwi.backgroundTask.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class RabbitBrokerVersionTest {

    enum class RabbitBrokerVersion(val version: String?, val compatible: Boolean?) {
        Minimum("4.4.0", compatible = true),
        MinimumWithRC("4.4.0-rc.1", compatible = true),
        LatestNightly("4.5.2+12.gabcdef", compatible = true),
        DoubleDigitMinor("4.10.0", compatible = true),
        NextMajor("5.0.0", compatible = true),
        InsufficientMinor("4.3.7", compatible = false),
        InsufficientMajor("3.13.7", compatible = false),
        MissingMinor("4", compatible = null),
        UnknownVersion("unknown", compatible = null),
        MissingVersion(null, compatible = null),
    }

    @ParameterizedTest
    @EnumSource(RabbitBrokerVersion::class)
    fun supportsDelayedMessages(expected: RabbitBrokerVersion) {
        assertEquals(expected.compatible, brokerSupportsDelayedMessages(expected.version))
    }
}
