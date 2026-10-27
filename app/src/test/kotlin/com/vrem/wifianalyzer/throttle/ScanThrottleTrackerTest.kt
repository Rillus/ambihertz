/*
 * WiFiAnalyzer
 * Copyright (C) 2015 - 2026 Ambihertz contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */
package com.vrem.wifianalyzer.throttle

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class ScanThrottleTrackerTest {
    private lateinit var fixture: ScanThrottleTracker

    @Before
    fun setUp() {
        fixture = ScanThrottleTracker()
    }

    @Test
    fun snapshotWithoutResultsIsWaiting() {
        val snapshot =
            fixture.snapshot(
                ScanSource.WIFI,
                nowMillis = 10_000L,
                environment = ScanEnvironment(permissionGranted = true, locationEnabled = true),
            )

        assertThat(snapshot.lastResultMillis).isNull()
        assertThat(snapshot.secondsSinceLast).isNull()
        assertThat(snapshot.resultCount).isZero
        assertThat(snapshot.distinctDeviceCount).isZero
        assertThat(snapshot.averageGapSeconds).isNull()
        assertThat(snapshot.longestGapSeconds).isNull()
        assertThat(snapshot.staleReason).isEqualTo(StaleReason.WAITING)
    }

    @Test
    fun recordCountsResultsAndDistinctDevices() {
        fixture.record(
            ScanSource.WIFI,
            atMillis = 1_000L,
            deviceIds = listOf("aa:aa", "bb:bb", "aa:aa"),
            rssiValues = listOf(-40, -70, -40),
        )

        val snapshot =
            fixture.snapshot(
                ScanSource.WIFI,
                nowMillis = 2_000L,
                environment = healthyEnvironment(),
            )

        assertThat(snapshot.resultCount).isEqualTo(3)
        assertThat(snapshot.distinctDeviceCount).isEqualTo(2)
        assertThat(snapshot.lastResultMillis).isEqualTo(1_000L)
        assertThat(snapshot.secondsSinceLast).isEqualTo(1L)
        assertThat(snapshot.staleReason).isEqualTo(StaleReason.RECEIVING)
    }

    @Test
    fun distinctDevicesAccumulateAcrossBatches() {
        fixture.record(ScanSource.BLE, 1_000L, listOf("one", "two"), listOf(-50, -60))
        fixture.record(ScanSource.BLE, 2_000L, listOf("two", "three"), listOf(-55, -80))

        val snapshot = fixture.snapshot(ScanSource.BLE, 2_500L, healthyEnvironment())

        assertThat(snapshot.resultCount).isEqualTo(4)
        assertThat(snapshot.distinctDeviceCount).isEqualTo(3)
    }

    @Test
    fun sourcesAreTrackedIndependently() {
        fixture.record(ScanSource.WIFI, 1_000L, listOf("wifi-1"), listOf(-30))
        fixture.record(ScanSource.BLE, 4_000L, listOf("ble-1", "ble-2"), listOf(-70, -90))

        val wifi = fixture.snapshot(ScanSource.WIFI, 5_000L, healthyEnvironment())
        val ble = fixture.snapshot(ScanSource.BLE, 5_000L, healthyEnvironment())

        assertThat(wifi.resultCount).isEqualTo(1)
        assertThat(wifi.distinctDeviceCount).isEqualTo(1)
        assertThat(wifi.lastResultMillis).isEqualTo(1_000L)
        assertThat(ble.resultCount).isEqualTo(2)
        assertThat(ble.distinctDeviceCount).isEqualTo(2)
        assertThat(ble.lastResultMillis).isEqualTo(4_000L)
    }

    @Test
    fun gapsUseCompletedIntervalsOnly() {
        fixture.record(ScanSource.WIFI, 0L, listOf("a"), listOf(-40))
        fixture.record(ScanSource.WIFI, 10_000L, listOf("b"), listOf(-50))
        fixture.record(ScanSource.WIFI, 40_000L, listOf("c"), listOf(-60))

        val snapshot = fixture.snapshot(ScanSource.WIFI, 41_000L, healthyEnvironment())

        assertThat(snapshot.averageGapSeconds).isCloseTo(20.0, within(0.01))
        assertThat(snapshot.longestGapSeconds).isEqualTo(30L)
    }

    @Test
    fun singleBatchHasNoGaps() {
        fixture.record(ScanSource.WIFI, 5_000L, listOf("a"), listOf(-40))

        val snapshot = fixture.snapshot(ScanSource.WIFI, 6_000L, healthyEnvironment())

        assertThat(snapshot.averageGapSeconds).isNull()
        assertThat(snapshot.longestGapSeconds).isNull()
    }

    @Test
    fun recordReturnsBatchSummaryWithoutIdentifiers() {
        val batch =
            fixture.record(
                ScanSource.BLE,
                8_000L,
                listOf("alpha", "beta", "alpha"),
                listOf(-62, -41, -80),
            )

        assertThat(batch.source).isEqualTo(ScanSource.BLE)
        assertThat(batch.atMillis).isEqualTo(8_000L)
        assertThat(batch.resultCount).isEqualTo(3)
        assertThat(batch.distinctInBatch).isEqualTo(2)
        assertThat(batch.strongestRssi).isEqualTo(-41)
    }

    @Test
    fun strongestRssiIsNullWhenBatchHasNoReadings() {
        val batch = fixture.record(ScanSource.WIFI, 1L, emptyList(), emptyList())

        assertThat(batch.strongestRssi).isNull()
        assertThat(batch.resultCount).isZero
        assertThat(batch.distinctInBatch).isZero
    }

    @Test
    fun permissionMissingTakesPriority() {
        fixture.record(ScanSource.WIFI, 1_000L, listOf("a"), listOf(-40))

        val snapshot =
            fixture.snapshot(
                ScanSource.WIFI,
                nowMillis = 2_000L,
                environment =
                    ScanEnvironment(
                        permissionGranted = false,
                        locationEnabled = false,
                        bluetoothEnabled = false,
                    ),
            )

        assertThat(snapshot.staleReason).isEqualTo(StaleReason.PERMISSION_MISSING)
    }

    @Test
    fun locationOffIsReportedWhenPermissionIsGranted() {
        val snapshot =
            fixture.snapshot(
                ScanSource.WIFI,
                nowMillis = 1_000L,
                environment = ScanEnvironment(permissionGranted = true, locationEnabled = false),
            )

        assertThat(snapshot.staleReason).isEqualTo(StaleReason.LOCATION_OFF)
    }

    @Test
    fun bluetoothOffIsReportedForBleOnly() {
        val environment =
            ScanEnvironment(
                permissionGranted = true,
                locationEnabled = true,
                bluetoothEnabled = false,
            )

        assertThat(fixture.snapshot(ScanSource.BLE, 1_000L, environment).staleReason)
            .isEqualTo(StaleReason.BLUETOOTH_OFF)
        assertThat(fixture.snapshot(ScanSource.WIFI, 1_000L, environment).staleReason)
            .isEqualTo(StaleReason.WAITING)
    }

    @Test
    fun wifiIsThrottledWhenLastResultIsOlderThanThreshold() {
        fixture.record(ScanSource.WIFI, 0L, listOf("a"), listOf(-40))

        val snapshot =
            fixture.snapshot(
                ScanSource.WIFI,
                nowMillis = ScanThrottleTracker.WIFI_THROTTLE_GAP_MS,
                environment = healthyEnvironment(),
            )

        assertThat(snapshot.staleReason).isEqualTo(StaleReason.THROTTLED)
        assertThat(snapshot.secondsSinceLast).isEqualTo(35L)
    }

    @Test
    fun bleIsThrottledWhenLastResultIsOlderThanThreshold() {
        fixture.record(ScanSource.BLE, 0L, listOf("a"), listOf(-70))

        val snapshot =
            fixture.snapshot(
                ScanSource.BLE,
                nowMillis = ScanThrottleTracker.BLE_THROTTLE_GAP_MS,
                environment = healthyEnvironment(),
            )

        assertThat(snapshot.staleReason).isEqualTo(StaleReason.THROTTLED)
    }

    @Test
    fun recentResultsAreNotThrottled() {
        fixture.record(ScanSource.WIFI, 0L, listOf("a"), listOf(-40))

        val snapshot =
            fixture.snapshot(
                ScanSource.WIFI,
                nowMillis = ScanThrottleTracker.WIFI_THROTTLE_GAP_MS - 1,
                environment = healthyEnvironment(),
            )

        assertThat(snapshot.staleReason).isEqualTo(StaleReason.RECEIVING)
    }

    @Test
    fun clearRemovesCountsAndGaps() {
        fixture.record(ScanSource.WIFI, 0L, listOf("a"), listOf(-40))
        fixture.record(ScanSource.BLE, 1_000L, listOf("b"), listOf(-80))

        fixture.clear()

        val wifi = fixture.snapshot(ScanSource.WIFI, 2_000L, healthyEnvironment())
        val ble = fixture.snapshot(ScanSource.BLE, 2_000L, healthyEnvironment())
        assertThat(wifi.resultCount).isZero
        assertThat(wifi.distinctDeviceCount).isZero
        assertThat(wifi.lastResultMillis).isNull()
        assertThat(ble.resultCount).isZero
        assertThat(ble.staleReason).isEqualTo(StaleReason.WAITING)
    }

    @Test
    fun csvLineContainsTimingAndCountsOnly() {
        val line = csvLine("2026-10-27T22:00:00.000+00:00", ScanSource.WIFI, 4, 3, -32)

        assertThat(line).isEqualTo("2026-10-27T22:00:00.000+00:00,wifi,4,3,-32")
        assertThat(line).doesNotContain("aa:")
        assertThat(line).doesNotContain("ssid")
    }

    @Test
    fun csvLineLeavesStrongestRssiEmptyWhenMissing() {
        assertThat(csvLine("ts", ScanSource.BLE, 0, 0, null)).isEqualTo("ts,ble,0,0,")
    }

    @Test
    fun isoTimestampIncludesTimezoneOffset() {
        val utc = TimeZone.getTimeZone("UTC")

        assertThat(isoTimestamp(0L, utc)).isEqualTo("1970-01-01T00:00:00.000Z")
    }

    @Test
    fun deviceKeyPrefersHardwareAddressWhenPresent() {
        assertThat(deviceKey("AA:BB:CC:DD:EE:FF", byteArrayOf(1, 2))).isEqualTo("aa:bb:cc:dd:ee:ff")
    }

    @Test
    fun deviceKeyUsesAdvertisementWhenAddressIsRedacted() {
        val advertisement = byteArrayOf(0x01, 0x0a, 0xFF.toByte())

        assertThat(deviceKey("02:00:00:00:00:00", advertisement)).isEqualTo("010aff")
        assertThat(deviceKey("00:00:00:00:00:00", advertisement)).isEqualTo("010aff")
        assertThat(deviceKey("", advertisement)).isEqualTo("010aff")
    }

    @Test
    fun deviceKeyFallsBackToBlankAddressWhenAdvertisementMissing() {
        assertThat(deviceKey("02:00:00:00:00:00", null)).isEqualTo("02:00:00:00:00:00")
    }

    private fun healthyEnvironment(): ScanEnvironment =
        ScanEnvironment(permissionGranted = true, locationEnabled = true, bluetoothEnabled = true)
}
