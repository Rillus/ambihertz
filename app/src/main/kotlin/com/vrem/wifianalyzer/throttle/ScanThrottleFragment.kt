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
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.vrem.wifianalyzer.MainContext
import com.vrem.wifianalyzer.R
import com.vrem.wifianalyzer.databinding.ScanThrottleContentBinding
import com.vrem.wifianalyzer.wifi.model.WiFiData
import com.vrem.wifianalyzer.wifi.scanner.collectWiFiData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanThrottleFragment : Fragment() {
    internal val tracker = ScanThrottleTracker()
    internal lateinit var fileLog: ScanThrottleFileLog
    internal lateinit var bleScanner: BleForegroundScanner

    private lateinit var binding: ScanThrottleContentBinding
    private val handler = Handler(Looper.getMainLooper())
    private var lastWifiFingerprint: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                bleScanner.start()
            }
            refresh()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = ScanThrottleContentBinding.inflate(inflater, container, false)
        fileLog = ScanThrottleFileLog(requireContext().getExternalFilesDir(null) ?: requireContext().filesDir)
        bleScanner =
            BleForegroundScanner(requireContext()) { deviceIds, rssiValues ->
                record(ScanSource.BLE, deviceIds, rssiValues)
            }
        binding.scanThrottleShare.setOnClickListener { shareLog() }
        binding.scanThrottleClear.setOnClickListener { clearLog() }
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.collectWiFiData(::onWiFiData)
    }

    override fun onResume() {
        super.onResume()
        requestBlePermissionOrStart()
        handler.post(tickRunnable)
        refresh()
    }

    override fun onPause() {
        handler.removeCallbacks(tickRunnable)
        bleScanner.stop()
        super.onPause()
    }

    private fun requestBlePermissionOrStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !bleScanner.hasScanPermission()) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            bleScanner.start()
        }
    }

    private fun onWiFiData(wiFiData: WiFiData) {
        val details = wiFiData.wiFiDetails
        if (details.isEmpty()) {
            refresh()
            return
        }
        val fingerprint =
            details
                .sortedBy { it.wiFiIdentifier.bssid }
                .joinToString { "${it.wiFiIdentifier.bssid}:${it.wiFiSignal.level}" }
        if (fingerprint == lastWifiFingerprint) {
            refresh()
            return
        }
        lastWifiFingerprint = fingerprint
        record(
            ScanSource.WIFI,
            details.map { it.wiFiIdentifier.bssid },
            details.map { it.wiFiSignal.level },
        )
    }

    private fun record(
        source: ScanSource,
        deviceIds: List<String>,
        rssiValues: List<Int>,
    ) {
        val batch = tracker.record(source, System.currentTimeMillis(), deviceIds, rssiValues)
        fileLog.append(batch)
        refresh()
    }

    private fun refresh() {
        if (!::binding.isInitialized) return
        val now = System.currentTimeMillis()
        bindSource(
            ScanSource.WIFI,
            tracker.snapshot(ScanSource.WIFI, now, wifiEnvironment()),
            binding,
        )
        bindSource(
            ScanSource.BLE,
            tracker.snapshot(ScanSource.BLE, now, bleEnvironment()),
            binding,
        )
    }

    private fun bindSource(
        source: ScanSource,
        stats: SourceStats,
        binding: ScanThrottleContentBinding,
    ) {
        val lastResult =
            stats.lastResultMillis?.let {
                getString(R.string.scan_throttle_last_result, formatClock(it), stats.secondsSinceLast ?: 0L)
            } ?: getString(R.string.scan_throttle_last_result_none)
        val averageGap =
            stats.averageGapSeconds?.let { getString(R.string.scan_throttle_gap_average, it) }
                ?: getString(R.string.scan_throttle_gap_none)
        val longestGap =
            stats.longestGapSeconds?.let { getString(R.string.scan_throttle_gap_seconds, it) }
                ?: getString(R.string.scan_throttle_gap_none)
        when (source) {
            ScanSource.WIFI -> {
                binding.scanThrottleWifiLast.text = lastResult
                binding.scanThrottleWifiCounts.text =
                    getString(R.string.scan_throttle_counts, stats.resultCount, stats.distinctDeviceCount)
                binding.scanThrottleWifiGaps.text = getString(R.string.scan_throttle_gaps, averageGap, longestGap)
                binding.scanThrottleWifiStatus.text = statusText(stats.staleReason)
            }
            ScanSource.BLE -> {
                binding.scanThrottleBleLast.text = lastResult
                binding.scanThrottleBleCounts.text =
                    getString(R.string.scan_throttle_counts, stats.resultCount, stats.distinctDeviceCount)
                binding.scanThrottleBleGaps.text = getString(R.string.scan_throttle_gaps, averageGap, longestGap)
                binding.scanThrottleBleStatus.text = statusText(stats.staleReason)
            }
        }
    }

    private fun statusText(reason: StaleReason): String =
        getString(
            when (reason) {
                StaleReason.RECEIVING -> R.string.scan_throttle_status_ok
                StaleReason.WAITING -> R.string.scan_throttle_status_waiting
                StaleReason.THROTTLED -> R.string.scan_throttle_status_throttled
                StaleReason.PERMISSION_MISSING -> R.string.scan_throttle_status_permission
                StaleReason.BLUETOOTH_OFF -> R.string.scan_throttle_status_bluetooth_off
                StaleReason.LOCATION_OFF -> R.string.scan_throttle_status_location_off
            },
        )

    private fun wifiEnvironment(): ScanEnvironment {
        val permissionService = MainContext.INSTANCE.permissionService
        return ScanEnvironment(
            permissionGranted = permissionService.permissionGranted(),
            locationEnabled = permissionService.locationEnabled(),
        )
    }

    private fun bleEnvironment(): ScanEnvironment =
        ScanEnvironment(
            permissionGranted = bleScanner.hasScanPermission(),
            locationEnabled = true,
            bluetoothEnabled = bleScanner.bluetoothEnabled(),
        )

    private fun shareLog() {
        if (!fileLog.hasEntries()) {
            Toast.makeText(requireContext(), R.string.scan_throttle_share_missing, Toast.LENGTH_LONG).show()
            return
        }
        val uri =
            FileProvider.getUriForFile(
                requireContext(),
                requireContext().packageName + FILE_PROVIDER_SUFFIX,
                fileLog.file,
            )
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, getString(R.string.scan_throttle_share_title))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        startActivity(Intent.createChooser(send, getString(R.string.scan_throttle_share)))
    }

    private fun clearLog() {
        tracker.clear()
        lastWifiFingerprint = null
        fileLog.clear()
        refresh()
        Toast.makeText(requireContext(), R.string.scan_throttle_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun formatClock(epochMillis: Long): String =
        SimpleDateFormat(CLOCK_PATTERN, Locale.getDefault()).format(Date(epochMillis))

    private val tickRunnable =
        object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, TICK_INTERVAL_MS)
            }
        }

    companion object {
        private const val TICK_INTERVAL_MS = 1_000L
        private const val CLOCK_PATTERN = "HH:mm:ss"
        private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    }
}
