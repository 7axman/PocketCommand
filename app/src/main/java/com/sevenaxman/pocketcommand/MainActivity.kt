package com.sevenaxman.pocketcommand

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "PocketCommand"
    }

    private enum class State(val label: String, val color: Int) {
        IDLE("IDLE (OFF)", 0xFF444444.toInt()),
        THINKING("THINKING...", 0xFFFF8800.toInt()),
        CRANKING("CRANKING!", 0xFFFFBB33.toInt()),
        RUNNING("ENGINE RUNNING", 0xFF669900.toInt()),
        COOLDOWN("COOLDOWN", 0xFF0099CC.toInt()),
        ERROR("SYSTEM ERROR", 0xFFCC0000.toInt())
    }

    private var port: UsbSerialPort? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var usbIoManager: SerialInputOutputManager? = null

    private lateinit var txtRxVolt: TextView
    private lateinit var txtState: TextView
    private lateinit var progressTxBat: ProgressBar
    private lateinit var txtStatus: TextView

    private val serialBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtRxVolt = findViewById(R.id.txtRxVolt)
        txtState = findViewById(R.id.txtState)
        progressTxBat = findViewById(R.id.progressTxBat)
        txtStatus = findViewById(R.id.txtStatus)

        findViewById<Button>(R.id.btnStart).setOnClickListener { sendCommand("!B1") }
        findViewById<Button>(R.id.btnLoad).setOnClickListener { sendCommand("!B3") }
        findViewById<Button>(R.id.btnCrank).setOnClickListener { sendCommand("!B2") }
        findViewById<Button>(R.id.btnStop).setOnClickListener { sendRaw("!OFF\n") }

        connectUsb()
    }

    private fun connectUsb() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (availableDrivers.isEmpty()) {
            txtStatus.text = "SEARCHING FOR REMOTE..."
            txtStatus.setTextColor(0xFFFF8800.toInt())
            return
        }

        val driver = availableDrivers[0]
        val connection = manager.openDevice(driver.device) ?: return

        port = driver.ports[0]
        port?.open(connection)
        port?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        usbIoManager = SerialInputOutputManager(port, this)
        Executors.newSingleThreadExecutor().submit(usbIoManager)
        
        txtStatus.text = "CONNECTED"
        txtStatus.setTextColor(0xFF669900.toInt())
    }

    private fun sendCommand(cmd: String) {
        sendRaw("$cmd\n")
        mainHandler.postDelayed({ sendRaw("!RELEASE\n") }, 300)
    }

    private fun sendRaw(data: String) {
        Log.d(TAG, "TX -> USB: $data")
        try {
            port?.write(data.toByteArray(), 100)
        } catch (e: Exception) {
            Log.e(TAG, "USB Write Error: ${e.message}")
            connectUsb()
        }
    }

    override fun onNewData(data: ByteArray) {
        serialBuffer.append(String(data))
        
        while (serialBuffer.contains("\n")) {
            val line = serialBuffer.substring(0, serialBuffer.indexOf("\n")).trim()
            serialBuffer.delete(0, serialBuffer.indexOf("\n") + 1)
            
            if (line.startsWith("!TEL:")) {
                Log.d(TAG, "USB -> RX: $line")
                processTelemetry(line)
            }
        }
    }

    private fun processTelemetry(message: String) {
        val parts = message.substring(5).trim().split(",")
        if (parts.size >= 4) {
            mainHandler.post {
                try {
                    val txMv = parts[0].toInt()
                    val rxV = parts[1].toFloat()
                    val stateIdx = parts[2].toInt()
                    val r3 = parts[3].toInt()

                    txtRxVolt.text = "${rxV}V"
                    
                    // TX Bat calculation (assuming Li-ion 3.3V-4.2V)
                    val pct = ((txMv - 3300) * 100 / 900).coerceIn(0, 100)
                    progressTxBat.progress = pct

                    val state = State.values().getOrElse(stateIdx % 16) { State.ERROR }
                    txtState.text = state.label
                    txtState.setBackgroundColor(state.color)
                    
                    if (r3 == 1) txtState.text = "${state.label} + LOAD"
                } catch (e: Exception) {
                    Log.e(TAG, "Parsing error: ${e.message}")
                }
            }
        }
    }

    override fun onRunError(e: Exception) {
        mainHandler.post { connectUsb() }
    }
}
