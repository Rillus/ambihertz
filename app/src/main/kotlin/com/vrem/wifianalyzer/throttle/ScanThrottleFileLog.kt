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

import java.io.File

class ScanThrottleFileLog(
    private val directory: File,
) {
    val file: File get() = File(directory, FILE_NAME)

    fun append(record: ScanBatchRecord) {
        ensureHeader()
        file.appendText(
            csvLine(
                isoTimestamp(record.atMillis),
                record.source,
                record.resultCount,
                record.distinctInBatch,
                record.strongestRssi,
            ) + "\n",
            Charsets.UTF_8,
        )
    }

    fun clear() {
        if (file.exists()) {
            file.delete()
        }
        ensureHeader()
    }

    fun hasEntries(): Boolean = file.exists() && file.length() > ScanThrottleTracker.CSV_HEADER.length + 1

    private fun ensureHeader() {
        if (!file.exists() || file.length() == 0L) {
            directory.mkdirs()
            file.writeText(ScanThrottleTracker.CSV_HEADER + "\n", Charsets.UTF_8)
        }
    }

    companion object {
        const val FILE_NAME = "scan_throttle.csv"
    }
}
