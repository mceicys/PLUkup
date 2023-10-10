package mceicys.plukup

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.core.math.MathUtils
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
collects and acknowledges checksum'd STX/ETX messages on the usb-serial connection. Use
mutex.withLock whenever accessing any public member variables. Don't interact with Recorder from
multiple threads. */
class Recorder(context: Context) {
    class Message(val bytes: ByteArray)

    val messages = mutableListOf<Message>()
    val mutex = Mutex()
    var numErrors = 0 // FIXME: save bad messages too but mark them so UI can display them with error coloring
        private set

    fun requestOpen() {
        if(isOpen())
            return

        val usb = heldContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)

        if(drivers.isEmpty())
            return

        val driver = drivers[0]
        heldContext.registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
            // FIXME: can this be registered multiple times?

        usb.requestPermission(driver.device, PendingIntent.getBroadcast(heldContext, 0,
            Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE))
    }

    fun isOpen() : Boolean {
        return serialConnection != null
    }

    fun close() {
        serialIO?.listener = null
        serialIO?.stop()
        serialIO = null
        serialConnection?.port?.close()
        serialConnection?.connection?.close()
        serialConnection = null
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

    private class SerialConnection(val connection: UsbDeviceConnection, val port: UsbSerialPort)

    private enum class ChecksumState {
        NONE, EXPECTED, GOOD, BAD
    }

    private val heldContext = context
    private var serialConnection: SerialConnection? = null
    private var serialIO: SerialInputOutputManager? = null
    private var messageLevel = 0
    private var tempMessage = ResizableByteArray(1024)
    private var checksum = 0
    private var checksumState = ChecksumState.NONE

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(receivedContext: Context, intent: Intent) {
            if (serialConnection == null &&
                intent.action == ACTION_USB_PERMISSION &&
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            ) {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    // FIXME: getParcelableExtra deprecated

                if (device == null)
                    return

                val usb = receivedContext.getSystemService(Context.USB_SERVICE) as UsbManager

                val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).first {
                    it.device == device
                }

                if (driver == null || driver.ports.isEmpty())
                    return

                val connection = usb.openDevice(device)

                if (connection == null)
                    return

                val port = driver.ports[0]
                port.open(connection)

                port.setParameters(
                    9600,
                    7,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_EVEN
                ) // FIXME: customizable by user

                port.dtr = true
                port.rts = true

                serialConnection = SerialConnection(connection, port)

                serialIO = SerialInputOutputManager(port, serialListener)
                serialIO?.start()
            }

            try {
                heldContext.unregisterReceiver(this)
            } catch(e: IllegalArgumentException) { // In case receiver is already unregistered
                null
            }
        }
    }

    private val serialListener = object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) {
            runBlocking { // FIXME: start a coroutine or does this thread not have any other work?
                receive(data)
            }
        }

        override fun onRunError(e: Exception?) {
            close() // FIXME: possible race condition?
        }
    }

    private suspend fun receive(data: ByteArray) {
        data.forEach {
            if(checksumState == ChecksumState.EXPECTED) {
                checksumState = if(checksum == it.toInt()) ChecksumState.GOOD else ChecksumState.BAD
                checksum = 0
                tempMessage.add(it)
            } else if(checksumState == ChecksumState.NONE) {
                if(messageLevel >= 2) {
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
                if(it.compareTo(ETX) == 0 || it.compareTo(ETB) == 0)
                    tempMessage.add(it)
                else
                    tempMessage.add(ETX.toByte())

                messageLevel = 0

                /* FIXME: It turns out the scale device considers anything wrapped with STX and ETX
                    to be an acknowledgement, the NACK doesn't do anything different; it might want
                    a specific text message */
                val selectAck: ByteArray? = when(checksumState) {
                    ChecksumState.GOOD -> ackBytes
                    ChecksumState.BAD -> nackBytes
                    else -> null
                }

                if(selectAck != null)
                    send(selectAck)

                /* FIXME: Class user can delay acknowledgement with this public mutex; put messages
                    into a private list that's transferred over to public in a coroutine? */
                if(checksumState == ChecksumState.GOOD) {
                    mutex.withLock {
                        messages.add(Message(tempMessage.toByteArray()))
                    }
                }
                else if(checksumState == ChecksumState.BAD) {
                    mutex.withLock {
                        numErrors++
                    }
                }

                tempMessage.clear()
                checksumState = ChecksumState.NONE
            }
        }
    }

    private fun send(data: ByteArray) {
        serialConnection?.port?.write(data, 0)
    }
}

fun binaryToString(bytes: ByteArray) : String {
    var str = ""
    var lastType = 0

    bytes.forEach {
        val i = it.toInt()
        val type = if(i >= 32 && i <= 126) 1 else -1

        if(type == 1) {
            if(lastType != 0 && lastType != type)
                str += ' '

            str += i.toChar()
        } else {
            if(lastType != 0)
                str += ' '

            str += '<' + i.toString() + '>'
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