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

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat

class BleForegroundScanner(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val onBatch: (deviceIds: List<String>, rssiValues: List<Int>) -> Unit,
) {
    private val callback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult,
            ) {
                deliver(listOf(result))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (results.isNotEmpty()) {
                    deliver(results)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
                if (errorCode != ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) {
                    scheduleRestart()
                }
            }
        }

    private var scanning = false
    private var started = false

    fun start() {
        started = true
        handler.removeCallbacks(restartRunnable)
        startScan()
    }

    fun stop() {
        started = false
        handler.removeCallbacks(restartRunnable)
        stopScan()
    }

    fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun bluetoothEnabled(): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BLUETOOTH_ON, 0) == BLUETOOTH_ON

    private fun startScan() {
        if (!started || scanning || !hasScanPermission() || !bluetoothEnabled()) {
            if (started) {
                scheduleRestart()
            }
            return
        }
        val scanner =
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: run {
                scheduleRestart()
                return
            }
        val settings =
            ScanSettings
                .Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
        runCatching {
            scanner.startScan(null, settings, callback)
            scanning = true
        }.onFailure {
            scanning = false
            scheduleRestart()
        }
    }

    private fun stopScan() {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        runCatching { scanner?.stopScan(callback) }
        scanning = false
    }

    private fun scheduleRestart() {
        if (!started) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, RESTART_DELAY_MS)
    }

    private val restartRunnable =
        Runnable {
            if (started) {
                stopScan()
                startScan()
            }
        }

    private fun deliver(results: List<ScanResult>) {
        val deviceIds =
            results.map { result ->
                val address = runCatching { result.device.address }.getOrDefault("")
                deviceKey(address, result.scanRecord?.bytes)
            }
        val rssiValues = results.map { it.rssi }
        onBatch(deviceIds, rssiValues)
    }

    companion object {
        const val RESTART_DELAY_MS = 6_000L
        private const val BLUETOOTH_ON = 1
    }
}
