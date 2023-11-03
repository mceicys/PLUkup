package mceicys.plukup

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.core.math.MathUtils
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.FileNotFoundException
import java.io.OutputStream
import java.lang.Exception
import java.lang.IllegalArgumentException
import kotlin.concurrent.thread

private const val ACTION_USB_PERMISSION = "mceicys.plukup.USB_PERMISSION"
private const val STX = 2
private const val ETX = 3
private const val ETB = 23
private val ackBytes = stringToBinary("@2@6@3")
private val nackBytes = stringToBinary("@2@21@3")

/* After requestOpen() is called and the user grants permission, Recorder starts a thread that
collects and acknowledges checksum'd STX/ETX messages on the usb-serial connection. Use numMessages
and getMessageCopy() to access these messages.

dataBits should be one of the constants UsbSerialPort.DATABITS_...
stopBits should be one of the constants UsbSerialPort.STOPBITS_...
parity should be one of the constants UsbSerialPort.PARITY_...*/
class Recorder(context: Context, baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {
    class Message(val bytes: ByteArray) {
        fun copyOf() : Message {
            return Message(bytes.copyOf())
        }
    }

    val numMessages: Int
        get() {synchronized(bigLock) {return messages.size}}

    val numErrors: Int
        get() {synchronized(bigLock) {return _numErrors}}

    fun getMessageCopy(i: Int) : Message {
        synchronized(bigLock) {
            return messages[i].copyOf()
        }
    }

    fun requestOpen() {
        if(isOpen())
            return

        val usb = heldContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)

        if(drivers.isEmpty())
            return

        val driver = drivers[0]

        if(Build.VERSION.SDK_INT >= 33) {
            heldContext.registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_EXPORTED)
        } else {
            heldContext.registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
        }

        usb.requestPermission(driver.device, PendingIntent.getBroadcast(heldContext, 0,
            Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE))
    }

    fun isOpen() : Boolean {
        return serialConnection != null
    }

    fun close() {
        synchronized(bigLock) {
            serialIO?.listener = null
            serialIO?.stop()
            serialIO = null

            try {
                serialConnection?.port?.close()
            } catch(_: java.io.IOException) {}

            serialConnection?.connection?.close()
            serialConnection = null
        }
    }

    fun clear() {
        synchronized(bigLock) {
            close()

            try {
                logStream?.close()
            } catch(_: java.io.IOException) {}

            logStream = null
            messages.clear()
            _numErrors = 0
            messageLevel = 0
            tempMessage.clear()
            checksum = 0
            checksumState = ChecksumState.NONE
        }
    }

    // Returns true if log set to given uri
    fun startLogging(uri: Uri) : Boolean {
        if (logStream != null)
            return false

        try {
            synchronized(bigLock) {
                logStream = heldContext.contentResolver.openOutputStream(uri)
            }
        } catch (e: FileNotFoundException) {
            return false
        }

        return true
    }

    // For testing; only call once
    fun replay(data: ByteArray) {
        thread {
            val step = 100
            var from = 0
            var to = 0

            while(from < data.size) {
                while(to - from < step && to < data.size) {
                    if(data[to].compareTo(ETX) == 0 || data[to].compareTo(ETB) == 0) {
                        to++
                        break
                    }

                    to++
                }

                serialListener.onNewData(data.copyOfRange(from, to))
                from = to
            }
        }
    }

    /*
    PRIVATE
    */

    private class SerialConnection(val connection: UsbDeviceConnection, val port: UsbSerialPort,
                                   var good: Boolean = true)

    private enum class ChecksumState {
        NONE, EXPECTED, GOOD, BAD
    }

    private val bigLock = Any() // Syncs class-user thread and internal serialListener thread

    private val messages = mutableListOf<Message>()
    private var _numErrors = 0 // FIXME: save bad messages too but mark them so UI can display them with error coloring
    private val heldContext = context
    private var serialConnection: SerialConnection? = null
    private var serialIO: SerialInputOutputManager? = null
    private var messageLevel = 0
    private var tempMessage = ResizableByteArray(1024)
    private var checksum = 0
    private var checksumState = ChecksumState.NONE
    private var logStream: OutputStream? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(receivedContext: Context, intent: Intent) {
            if (serialConnection == null &&
                intent.action == ACTION_USB_PERMISSION &&
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            ) {
                val device: UsbDevice = intent.getParcelableExtraUndeprecated(
                    UsbManager.EXTRA_DEVICE,
                    UsbDevice::class.java
                ) ?: return

                val usb = receivedContext.getSystemService(Context.USB_SERVICE) as UsbManager
                val driver: UsbSerialDriver

                try {
                    driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).first {
                        it.device == device
                    }
                } catch(e: NoSuchElementException) {
                    return
                }

                if (driver.ports.isEmpty())
                    return

                val connection: UsbDeviceConnection

                try {
                    connection = usb.openDevice(device) ?: return
                } catch(e: java.io.IOException) {
                    return
                }

                val port: UsbSerialPort

                try {
                    port = driver.ports[0]
                    port.open(connection)
                    port.setParameters(baudRate, dataBits, stopBits, parity)
                    port.dtr = true
                    port.rts = true
                } catch(e: java.io.IOException) {
                    connection.close()
                    return
                }

                synchronized(bigLock) {
                    serialConnection = SerialConnection(connection, port)
                    serialIO = SerialInputOutputManager(port, serialListener)
                    serialIO?.start()
                }
            }

            try {
                heldContext.unregisterReceiver(this)
            } catch(_: IllegalArgumentException) {} // In case receiver is already unregistered
        }
    }

    private val serialListener = object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) {
            receive(data)

            if(serialConnection?.good == false)
                close()
        }

        override fun onRunError(e: Exception?) {
            close()
        }
    }

    private fun receive(data: ByteArray) {
        synchronized(bigLock) {
            try {
                logStream?.write(data)
            } catch(_: java.io.IOException) {}

            data.forEach {
                if (checksumState == ChecksumState.EXPECTED) {
                    checksumState =
                        if (checksum == it.toInt()) ChecksumState.GOOD else ChecksumState.BAD
                    checksum = 0
                    tempMessage.add(it)
                } else if (checksumState == ChecksumState.NONE) {
                    if (messageLevel >= 2) {
                        checksum = (checksum xor it.toInt()) % 255
                    }

                    if (it.compareTo(STX) == 0) {
                        if (messageLevel == 0)
                            tempMessage.clear()

                        messageLevel++
                    }

                    tempMessage.add(it)

                    if (it.compareTo(ETX) == 0 || it.compareTo(ETB) == 0) {
                        messageLevel--

                        if (messageLevel == 1) {
                            checksumState = ChecksumState.EXPECTED
                        } else if (messageLevel < 0) {
                            send(ackBytes)
                            messageLevel = 0
                        }
                    }
                } else {
                    // Next byte after the checksum; assume it's ETX or ETB
                    if (it.compareTo(ETX) == 0 || it.compareTo(ETB) == 0)
                        tempMessage.add(it)
                    else
                        tempMessage.add(ETX.toByte())

                    messageLevel = 0

                    /* FIXME: It turns out the scale device considers anything wrapped with STX and ETX
                    to be an acknowledgement, the NACK doesn't do anything different; it might want
                    a specific text message */
                    val selectAck: ByteArray? = when (checksumState) {
                        ChecksumState.GOOD -> ackBytes
                        ChecksumState.BAD -> nackBytes
                        else -> null
                    }

                    if (selectAck != null)
                        send(selectAck)

                    if (checksumState == ChecksumState.GOOD) {
                        messages.add(Message(tempMessage.toByteArray()))
                    } else if (checksumState == ChecksumState.BAD) {
                        _numErrors++
                    }

                    tempMessage.clear()
                    checksumState = ChecksumState.NONE
                }
            }
        }
    }

    private fun send(data: ByteArray) {
        synchronized(bigLock) {
            try {
                serialConnection?.port?.write(data, 0)
            } catch(e: java.io.IOException) {
                serialConnection?.good = false
            }

            try {
                logStream?.write(data)
            } catch(_: java.io.IOException) {}
        }
    }
}

fun binaryToString(bytes: ByteArray) : String {
    var str = ""
    var lastType = 0

    bytes.forEach {
        val i = it.toInt()
        val type = if(i in 32..126) 1 else -1

        if(type == 1) {
            if(lastType != 0 && lastType != type)
                str += ' '

            str += i.toChar()
        } else {
            if(lastType != 0)
                str += ' '

            str += "<$i>"
        }

        lastType = type
    }

    return str
}

fun stringToBinary(str: String) : ByteArray {
    val bin = ByteArray(str.length)
    var r = 0
    var w = 0

    while(r < str.length) {
        if(str[r] == '@') {
            r++

            if(r < str.length) {
                if (str[r] == '@') {
                    bin[w++] = str[r].code.toByte()
                    r++
                } else {
                    var end = r

                    while(end < str.length && str[end].isDigit())
                        end++

                    if(end > r) {
                        try {
                            val ascii = MathUtils.clamp(str.substring(r, end).toInt(), 0, 255).toByte()
                            bin[w++] = ascii
                        } catch (e: NumberFormatException) { null }
                    }

                    r = end

                    if(r < str.length && str[r].isWhitespace())
                        r++ // So a space can be used as terminator
                }
            }
        } else {
            bin[w++] = str[r].code.toByte()
            r++
        }
    }

    return bin.copyOfRange(0, w)
}

fun <T> Intent.getParcelableExtraUndeprecated(name: String, clazz: Class<T>): T? {
    if(Build.VERSION.SDK_INT >= 33) {
        return getParcelableExtra(name, clazz)
    } else {
        return getParcelableExtra(name)
    }
}