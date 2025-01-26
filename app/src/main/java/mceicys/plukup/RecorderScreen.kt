package mceicys.plukup

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ACTION_USB_DEVICE_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
private const val ERROR_SAME_FILE = "ERROR: PLU file and debug file cannot be the same"

class RecorderScreen(private val activity: MainActivity) {
    // Call in activity's onCreate
    fun onCreate() {
        recorder.requestOpen() // Since device might already be attached
        activity.registerReceiver(attachmentReceiver, IntentFilter(ACTION_USB_DEVICE_ATTACHED))

        // Create a looping job that checks what Recorder's thread has been up to
        if(pollJob == null) {
            pollJob = CoroutineScope(Dispatchers.Main).launch {
                while (true) {
                    recorderOpen = recorder.isOpen()

                    // Parse new messages and add to or modify database
                    // Unoptimized, but the serial transfer will always be the bottleneck
                    while(numParsed < recorder.numMessages) {
                        val prods = parseSerialProducts(recorder.getMessageCopy(numParsed).bytes)
                        activity.mergeChanges(null, prods, false)
                        numParsed++
                    }

                    // Add messages to UI
                    for (i in translations.size until recorder.numMessages) {
                        translations.add(binaryToString(recorder.getMessageCopy(i).bytes))
                    }

                    numErrors = recorder.numErrors

                    delay(1)
                }
            }
        }
    }

    // Call in activity's onDestroy
    fun onDestroy() {
        pollJob?.cancel()
        pollJob = null
        recorder.clear()
        activity.unregisterReceiver(attachmentReceiver)
    }

    @Composable
    fun RecorderUI() {
        val lazyColumnState = rememberLazyListState()
        val coroutineScope = rememberCoroutineScope()
        var savedIndex by remember { mutableIntStateOf(-1) }
        val colors = MaterialTheme.colorScheme
        val debugPath = saveDebugUri?.path
        val BUTTON_WIDTH = 140.dp
        val BUTTON_HEIGHT = 52.dp

        SidePaddingParent {
            Surface(color = colors.background) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopPadding()

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .weight(1.0f)
                                .fillMaxWidth()
                                .padding(8.dp)
                        ) {
                            if (recorderOpen) {
                                Text(
                                    "Have serial connection!",
                                    color = colors.onBackground,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text(
                                    "No serial connection",
                                    color = colors.onError,
                                    modifier = Modifier
                                        .background(colors.error)
                                        .fillMaxWidth()
                                )
                            }

                            val path = selectedWorkFile?.name
                            val fileModifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    repeatDelayMillis = 0,
                                    velocity = 50.dp
                                )

                            if (path == null) {
                                Text(
                                    "No destination file",
                                    color = colors.onError,
                                    modifier = Modifier
                                        .background(colors.error)
                                        .fillMaxWidth()
                                )
                            } else {
                                Text(
                                    "Saving to \"${path.substringAfterLast(':')}\"",
                                    color = colors.onBackground,
                                    maxLines = 1,
                                    modifier = fileModifier
                                )
                            }

                            if (debugPath != null) {
                                Text(
                                    "Writing raw output to \"${debugPath.substringAfterLast(':')}\"",
                                    color = colors.onBackground,
                                    maxLines = 1,
                                    modifier = fileModifier
                                )
                            }

                            Text(
                                "Parsed : ${numParsed}/${translations.size}",
                                color = colors.onBackground
                            )

                            val numErr = numErrors

                            Text(
                                "Errors: $numErr",
                                color = if (numErr == 0) colors.onBackground else colors.onError,
                                modifier = Modifier.background(if (numErr == 0) colors.background else colors.error)
                            )
                        }

                        Column {
                            if (debugPath == null) {
                                CustomButton(
                                    onLongClick = {
                                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                            type = "*/*"
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                            putExtra(Intent.EXTRA_TITLE, "raw_output")
                                        }

                                        setLogLauncher.launch(intent)
                                    },
                                    modifier = Modifier.size(BUTTON_WIDTH, BUTTON_HEIGHT),
                                    borderColor = colors.background,
                                    contentColor = colors.background,
                                    backgroundColor = colors.primary
                                ) {
                                    Text(
                                        "New Debug File",
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1.0f)
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(width = 2.dp, color = colors.onBackground),
                            state = lazyColumnState
                        ) {
                            val lastIndex = translations.lastIndex

                            if (lastIndex != savedIndex) {
                                coroutineScope.launch {
                                    lazyColumnState.scrollToItem(Integer.max(0, lastIndex))
                                }

                                savedIndex = lastIndex
                            }

                            itemsIndexed(translations) { index, it ->
                                val backColor: Color
                                val textColor: Color

                                if (index < numParsed) {
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

                    BottomPadding(0.dp)
                }
            }
        }
    }

    /*
    PRIVATE
    */

    private val translations = mutableStateListOf<String>()
    private var recorderOpen by mutableStateOf(false)
    private var saveDebugUri by mutableStateOf<Uri?>(null)
    private var numParsed by mutableIntStateOf(0)
    private var numErrors by mutableIntStateOf(0)

    private val recorder = Recorder(activity, 9600, UsbSerialPort.DATABITS_7,
        UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN)

    private var pollJob: Job? = null

    private val attachmentReceiver = object : BroadcastReceiver() {
        override fun onReceive(receivedContext: Context, intent: Intent) {
            if (intent.action == ACTION_USB_DEVICE_ATTACHED) {
                recorder.requestOpen()
            }
        }
    }

    private val setLogLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if(result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data

            if(uri != null) {
                if(uri.lastPathSegment == selectedWorkFile?.uri?.lastPathSegment) {
                    Toast.makeText(activity, ERROR_SAME_FILE, Toast.LENGTH_LONG).show()
                } else {
                    if (recorder.startLogging(uri))
                        saveDebugUri = uri
                }
            }
        }
    }
}