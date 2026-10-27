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

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ScanSource {
    WIFI,
    BLE,
    ;

    val csvName: String
        get() =
            when (this) {
                WIFI -> "wifi"
                BLE -> "ble"
            }
}

enum class StaleReason {
    RECEIVING,
    WAITING,
    THROTTLED,
    PERMISSION_MISSING,
    BLUETOOTH_OFF,
    LOCATION_OFF,
}

data class ScanEnvironment(
    val permissionGranted: Boolean,
    val locationEnabled: Boolean,
    val bluetoothEnabled: Boolean = true,
)

data class ScanBatchRecord(
    val source: ScanSource,
    val atMillis: Long,
    val resultCount: Int,
    val distinctInBatch: Int,
    val strongestRssi: Int?,
)

data class SourceStats(
    val lastResultMillis: Long?,
    val secondsSinceLast: Long?,
    val resultCount: Int,
    val distinctDeviceCount: Int,
    val averageGapSeconds: Double?,
    val longestGapSeconds: Long?,
    val staleReason: StaleReason,
)

class ScanThrottleTracker {
    private val timestamps = mutableMapOf<ScanSource, MutableList<Long>>()
    private val resultCounts = mutableMapOf<ScanSource, Int>()
    private val devices = mutableMapOf<ScanSource, MutableSet<String>>()

    fun record(
        source: ScanSource,
        atMillis: Long,
        deviceIds: List<String>,
        rssiValues: List<Int>,
    ): ScanBatchRecord {
        timestamps.getOrPut(source) { mutableListOf() }.add(atMillis)
        resultCounts[source] = resultCount(source) + deviceIds.size
        devices.getOrPut(source) { mutableSetOf() }.addAll(deviceIds)
        return ScanBatchRecord(
            source = source,
            atMillis = atMillis,
            resultCount = deviceIds.size,
            distinctInBatch = deviceIds.toSet().size,
            strongestRssi = rssiValues.maxOrNull(),
        )
    }

    fun snapshot(
        source: ScanSource,
        nowMillis: Long,
        environment: ScanEnvironment,
    ): SourceStats {
        val sourceTimestamps = timestamps[source].orEmpty()
        val lastResultMillis = sourceTimestamps.lastOrNull()
        val secondsSinceLast = lastResultMillis?.let { ((nowMillis - it) / 1000L).coerceAtLeast(0L) }
        val gapsSeconds = sourceTimestamps.zipWithNext { start, end -> (end - start) / 1000L }
        return SourceStats(
            lastResultMillis = lastResultMillis,
            secondsSinceLast = secondsSinceLast,
            resultCount = resultCount(source),
            distinctDeviceCount = devices[source].orEmpty().size,
            averageGapSeconds = gapsSeconds.takeIf { it.isNotEmpty() }?.average(),
            longestGapSeconds = gapsSeconds.maxOrNull(),
            staleReason = staleReason(source, secondsSinceLast, environment),
        )
    }

    fun clear() {
        timestamps.clear()
        resultCounts.clear()
        devices.clear()
    }

    private fun resultCount(source: ScanSource): Int = resultCounts[source] ?: 0

    private fun staleReason(
        source: ScanSource,
        secondsSinceLast: Long?,
        environment: ScanEnvironment,
    ): StaleReason {
        if (!environment.permissionGranted) return StaleReason.PERMISSION_MISSING
        if (!environment.locationEnabled) return StaleReason.LOCATION_OFF
        if (source == ScanSource.BLE && !environment.bluetoothEnabled) return StaleReason.BLUETOOTH_OFF
        if (secondsSinceLast == null) return StaleReason.WAITING
        val throttleGapMs =
            when (source) {
                ScanSource.WIFI -> WIFI_THROTTLE_GAP_MS
                ScanSource.BLE -> BLE_THROTTLE_GAP_MS
            }
        return if (secondsSinceLast * 1000L >= throttleGapMs) {
            StaleReason.THROTTLED
        } else {
            StaleReason.RECEIVING
        }
    }

    companion object {
        const val WIFI_THROTTLE_GAP_MS = 35_000L
        const val BLE_THROTTLE_GAP_MS = 10_000L
        const val CSV_HEADER = "timestamp,source,result_count,distinct_devices,strongest_rssi"
    }
}

fun csvLine(
    timestampIso: String,
    source: ScanSource,
    resultCount: Int,
    distinctInBatch: Int,
    strongestRssi: Int?,
): String =
    listOf(
        timestampIso,
        source.csvName,
        resultCount.toString(),
        distinctInBatch.toString(),
        strongestRssi?.toString().orEmpty(),
    ).joinToString(",")

fun isoTimestamp(
    epochMillis: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val format = SimpleDateFormat(ISO_TIMESTAMP_PATTERN, Locale.US)
    format.timeZone = timeZone
    return format.format(Date(epochMillis))
}

fun deviceKey(
    address: String?,
    advertisementBytes: ByteArray?,
): String {
    val normalized = address?.trim().orEmpty()
    if (normalized.isNotEmpty() && !isRedactedAddress(normalized)) {
        return normalized.lowercase(Locale.US)
    }
    return advertisementBytes?.joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    } ?: normalized.lowercase(Locale.US)
}

private fun isRedactedAddress(address: String): Boolean {
    val compact = address.lowercase(Locale.US).replace(":", "")
    return compact == REDACTED_ADDRESS || compact == ZERO_ADDRESS
}

private const val ISO_TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
private const val REDACTED_ADDRESS = "020000000000"
private const val ZERO_ADDRESS = "000000000000"
