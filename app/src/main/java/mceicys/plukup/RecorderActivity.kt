package mceicys.plukup

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mceicys.plukup.ui.theme.PLUkupTheme
import java.io.OutputStream

private const val ACTION_USB_DEVICE_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
private const val ERROR_SAME_FILE = "ERROR: PLU file and debug file cannot be the same"

class RecorderActivity : ComponentActivity() {
    val recorder = Recorder(this, 9600, UsbSerialPort.DATABITS_7,
        UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN)

    val recorderOpen = mutableStateOf(false)
    val translations = mutableStateListOf<String>()
    val saveUri = mutableStateOf<Uri?>(null)
    val saveDebugUri = mutableStateOf<Uri?>(null)
    val numSaved = mutableStateOf(0)
    val numErrors = mutableStateOf(0)
    var outputStream: OutputStream? = null
    var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder.requestOpen() // Since device might already be attached
        registerReceiver(attachmentReceiver, IntentFilter(ACTION_USB_DEVICE_ATTACHED))

        if(pollJob == null) {
            pollJob = CoroutineScope(Dispatchers.Main).launch {
                while (true) {
                    recorderOpen.value = recorder.isOpen()

                    if (outputStream != null) {
                        while (numSaved.value < recorder.numMessages) {
                            try {
                                outputStream!!.write(recorder.getMessageCopy(numSaved.value).bytes)
                                numSaved.value++
                            } catch (e: NullPointerException) {
                                break
                            }
                        }
                    }

                    for (i in translations.size until recorder.numMessages) {
                        translations.add(binaryToString(recorder.getMessageCopy(i).bytes))
                    }

                    numErrors.value = recorder.numErrors

                    delay(1)
                }
            }
        }

        setContent {
            PLUkupTheme {
                RecorderUI()
            }
        }
    }

    val attachmentReceiver = object : BroadcastReceiver() {
        override fun onReceive(receivedContext: Context, intent: Intent) {
            if (intent.action == ACTION_USB_DEVICE_ATTACHED) {
                recorder.requestOpen()
            }
        }
    }

    fun closeFileStream() {
        outputStream?.close()
        outputStream = null
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        pollJob = null
        recorder.clear()
        closeFileStream()
        unregisterReceiver(attachmentReceiver)
    }

    val setFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if(result.resultCode == Activity.RESULT_OK) {
            val intent = result.data
            val uri = intent?.data

            if(uri == saveDebugUri.value) {
                Toast.makeText(this, ERROR_SAME_FILE, Toast.LENGTH_LONG).show()
            } else {
                saveUri.value = uri
                closeFileStream()
                numSaved.value = 0

                if (uri != null) {
                    outputStream = contentResolver.openOutputStream(uri)
                }
            }
        }
    }

    val setLogLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if(result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data

            if(uri != null) {
                if(uri == saveUri.value) {
                    Toast.makeText(this, ERROR_SAME_FILE, Toast.LENGTH_LONG).show()
                } else {
                    if (recorder.startLogging(uri))
                        saveDebugUri.value = uri
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecorderUI() {
    val context = LocalContext.current
    val activity = context as RecorderActivity
    val lazyColumnState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val savedIndex = remember {mutableStateOf(-1)}
    val colors = MaterialTheme.colorScheme
    val BUTTON_WIDTH = 140.dp
    val BUTTON_HEIGHT = 52.dp
    val debugPath = activity.saveDebugUri.value?.path

    Surface(color = colors.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1.0f).fillMaxWidth().padding(8.dp)) {
                    if (activity.recorderOpen.value) {
                        Text("Have serial connection!", color = colors.onBackground, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text("No serial connection", color = colors.onError, modifier = Modifier.background(colors.error).fillMaxWidth())
                    }

                    val path = activity.saveUri.value?.path
                    val fileModifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, delayMillis = 0, velocity = 50.dp)

                    if(path == null) {
                        Text("No destination file", color = colors.onError, modifier = Modifier.background(colors.error).fillMaxWidth())
                    } else {
                        Text(
                            "Saving to \"${path.substringAfterLast(':')}\"",
                            color = colors.onBackground,
                            maxLines = 1,
                            modifier = fileModifier
                        )
                    }

                    if(debugPath != null) {
                        Text(
                            "Writing raw output to \"${debugPath.substringAfterLast(':')}\"",
                            color = colors.onBackground,
                            maxLines = 1,
                            modifier = fileModifier
                        )
                    }

                    Text("Saved : ${activity.numSaved.value}/${activity.translations.size}", color = colors.onBackground)

                    val numErr = activity.numErrors.value

                    Text("Errors: $numErr",
                        color = if(numErr == 0) colors.onBackground else colors.onError,
                        modifier = Modifier.background(if(numErr == 0) colors.background else colors.error)
                    )
                }

                Column {
                    CustomButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                type = "*/*"
                                addCategory(Intent.CATEGORY_OPENABLE)
                                putExtra(Intent.EXTRA_TITLE, "products.plu")
                            }

                            activity.setFileLauncher.launch(intent)
                        },
                        modifier = Modifier.size(BUTTON_WIDTH, BUTTON_HEIGHT)
                    ) {
                        Text(
                            "New PLU File",
                            textAlign = TextAlign.Center
                        )
                    }

                    if(debugPath == null) {
                        CustomButton(
                            onLongClick = {
                                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    type = "*/*"
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    putExtra(Intent.EXTRA_TITLE, "raw_output")
                                }

                                activity.setLogLauncher.launch(intent)
                            },
                            modifier = Modifier.size(BUTTON_WIDTH, BUTTON_HEIGHT),
                            borderColor = colors.secondary
                        ) {
                            Text(
                                "New Debug File (Hold)",
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize().weight(1.0f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().border(width = 2.dp, color = colors.onBackground),
                    state = lazyColumnState
                ) {
                    val lastIndex = activity.translations.lastIndex

                    if (lastIndex != savedIndex.value) {
                        coroutineScope.launch {
                            lazyColumnState.scrollToItem(Integer.max(0, lastIndex))
                        }

                        savedIndex.value = lastIndex
                    }

                    itemsIndexed(activity.translations) { index, it ->
                        val backColor: Color
                        val textColor: Color

                        if(index < activity.numSaved.value) {
                            backColor = colors.secondary
                            textColor = colors.onSecondary
                        } else {
                            backColor = colors.error
                            textColor = colors.onError
                        }

                        Row(modifier = Modifier.background(backColor)) {
                            Text(
                                it,
                                color = textColor,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}