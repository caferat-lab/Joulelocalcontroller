package com.example.joulelocal

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ActivityCompat
import java.util.UUID

class JouleBleManager(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onTemperature: (Float, Long, Long) -> Unit,
    private val onReady: () -> Unit
) {
    companion object {
        const val SERVICE = "700b4321-9836-4383-a2b2-31a9098d1473"
        const val WRITE = "700b4322-9836-4383-a2b2-31a9098d1473"
        const val READ = "700b4323-9836-4383-a2b2-31a9098d1473"
        const val NOTIFY = "700b4325-9836-4383-a2b2-31a9098d1473"
        const val SERVICE_CHANGED = "00002a05-0000-1000-8000-00805f9b34fb"
        const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter
    private val scanner get() = adapter.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var readChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null
    private var connected = false
    private var liveFeedStarted = false
    private val prefs = context.getSharedPreferences("joule", Context.MODE_PRIVATE)

    private fun hasConnect(): Boolean =
        Build.VERSION.SDK_INT < 31 || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasScan(): Boolean =
        Build.VERSION.SDK_INT < 31 || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    fun startScan() {
        if (!hasScan()) {
            onStatus("Bluetooth scan permission is missing.")
            return
        }
        if (!adapter.isEnabled) {
            onStatus("Turn Bluetooth on first.")
            return
        }
        onStatus("Scanning for Joule…")
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(
                android.os.ParcelUuid.fromString(SERVICE)
            ).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val name = result.scanRecord?.deviceName ?: d.name ?: ""
                if (name.contains("joule", true)) {
                    stopScan()
                    onStatus("Found $name. Connecting…")
                    connect(d)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                onStatus("Bluetooth scan failed: $errorCode")
            }
        }
        scanner.startScan(filters, settings, scanCallback)
        Handler(Looper.getMainLooper()).postDelayed({
            if (scanCallback != null) {
                stopScan()
                onStatus("Scan finished. If your Joule was not found, keep it powered on and try again.")
            }
        }, 15000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { scanner.stopScan(it) }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        gatt?.close()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                onStatus("Connected. Discovering services…")
                g.requestMtu(185)
                g.discoverServices()
            } else {
                connected = false
                liveFeedStarted = false
                onStatus("Disconnected (status $status).")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("Service discovery failed: $status")
                return
            }
            val service = g.getService(UUID.fromString(SERVICE))
            writeChar = service?.getCharacteristic(UUID.fromString(WRITE))
            readChar = service?.getCharacteristic(UUID.fromString(READ))
            notifyChar = service?.getCharacteristic(UUID.fromString(NOTIFY))
            if (writeChar == null || readChar == null || notifyChar == null) {
                onStatus("Joule service found, but required characteristics are missing.")
                return
            }
            onStatus("Joule service ready. Enabling notifications…")
            enableDataNotifications(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.characteristic.uuid == UUID.fromString(NOTIFY)) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    authenticateOrStartFeed()
                } else onStatus("Could not enable Joule data notifications: $status")
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == UUID.fromString(NOTIFY)) {
                // 4325 is only a "data ready" signal. Read the actual protobuf from 4323.
                g.readCharacteristic(readChar)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                onCharacteristicChanged(g, characteristic, characteristic.value ?: ByteArray(0))
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (characteristic.uuid != UUID.fromString(READ)) return
            try {
                val decoded = JouleProto.decodeStream(value)
                decoded.secretKey?.let {
                    prefs.edit().putString("key", it.joinToString("") { b -> "%02x".format(b) }).apply()
                    onStatus("Joule authorized. Starting live temperature feed…")
                    submitKeyAndStart(it)
                }
                decoded.authResult?.let { result ->
                    if (result == 0L) {
                        onStatus("Authorized. Starting live temperature feed…")
                        send(JouleProto.beginLiveFeed(1))
                    } else onStatus("Joule rejected the authorization key (result $result).")
                }
                decoded.dataPoint?.let { p ->
                    onTemperature(p.bathTempC, p.feedId, p.sequence)
                    if (!liveFeedStarted) {
                        liveFeedStarted = true
                        onReady()
                    }
                }
            } catch (e: Exception) {
                onStatus("Protocol decode error: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableDataNotifications(g: BluetoothGatt) {
        val c = notifyChar ?: return
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(UUID.fromString(CCCD))
        if (d == null) {
            authenticateOrStartFeed()
            return
        }
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(d)
    }

    @SuppressLint("MissingPermission")
    private fun authenticateOrStartFeed() {
        val stored = prefs.getString("key", null)
        if (stored.isNullOrBlank()) {
            onStatus("Press the button on top of the Joule when it starts flashing.")
            send(JouleProto.startKeyExchange())
        } else {
            val key = hexToBytes(stored)
            onStatus("Using saved Joule authorization key…")
            send(JouleProto.submitKey(key))
        }
    }

    @SuppressLint("MissingPermission")
    private fun submitKeyAndStart(key: ByteArray) {
        send(JouleProto.submitKey(key))
    }

    @SuppressLint("MissingPermission")
    private fun send(payload: ByteArray) {
        val g = gatt ?: return
        val c = writeChar ?: return
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            c.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    @SuppressLint("MissingPermission")
    @SuppressLint("MissingPermission")
fun startCook(
    targetC: Float,
    cookTimeSeconds: Long,
    feedId: Long,
    sequence: Long
) {
    if (!connected) {
        onStatus("Not connected.")
        return
    }

    onStatus("Preparing Joule to start…")

    // The Joule expects an identify command before starting a program.
    send(JouleProto.identifyCirculator())

    Handler(Looper.getMainLooper()).postDelayed({
        onStatus("Sending cooking command…")
        send(
            JouleProto.startCook(
                targetC,
                cookTimeSeconds,
                feedId,
                sequence
            )
        )
    }, 500)
}

    @SuppressLint("MissingPermission")
    fun stopCook(feedId: Long, sequence: Long) {
        if (!connected) { onStatus("Not connected."); return }
        onStatus("Stopping cook…")
        send(JouleProto.stopCook(feedId, sequence))
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        connected = false
    }

    private fun hexToBytes(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
