package mceicys.plukup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import mceicys.plukup.ui.theme.PLUkupTheme
import java.io.OutputStream

/* FIXME: Activity crashes (flashes and goes back to MainActivity) after unplugging once all data
    is transferred; reacts fine to unplugging in tests without any received messages */
/* FIXME: One data transfer test missed one or more products (PLU 1089); No checksum errors; Hard to
    debug, Recorder should keep a log of all traffic and this activity should save it to an
    app-internal file */
class RecorderActivity : ComponentActivity() {
    val recorder = Recorder(this)
    val recorderOpen = mutableStateOf(false)
    val translations = mutableStateListOf<String>()
    val saveUri = mutableStateOf<Uri?>(null)
    val numSaved = mutableStateOf(0)
    val numErrors = mutableStateOf(0)
    var outputStream: OutputStream? = null
    var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if(job == null) {
            job = CoroutineScope(Dispatchers.Main).launch {
                while (true) {
                    recorderOpen.value = recorder.isOpen()

                    /* FIXME: if the user does not allow permission, he's asked again immediately
                        * usb-serial API has an event for detecting when cable is plugged in
                        * ask for permission on activity start, and if denied, ask when cable is plugged in */
                    if (!recorder.isOpen())
                        recorder.requestOpen()

                    recorder.mutex.withLock {
                        if (outputStream != null) {
                            while (numSaved.value < recorder.messages.size) {
                                try {
                                    outputStream!!.write(recorder.messages[numSaved.value].bytes)
                                    numSaved.value++
                                } catch (e: NullPointerException) {
                                    break
                                }
                            }
                        }

                        for (i in translations.size..recorder.messages.size - 1) {
                            translations.add(binaryToString(recorder.messages[i].bytes))
                        }

                        numErrors.value = recorder.numErrors
                    }

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

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        job = null
        recorder.close()
        outputStream?.close()
    }

    val setFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if(result.resultCode == Activity.RESULT_OK) {
            val intent = result.data
            val uri = intent?.data
            saveUri.value = uri
            outputStream?.close()
            numSaved.value = 0

            if(uri != null) {
                outputStream = contentResolver.openOutputStream(uri)
            }
        }
    }
}

@Composable
fun RecorderUI(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as RecorderActivity
    val lazyColumnState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var savedIndex by remember {mutableStateOf(-1)}
    val colors = MaterialTheme.colorScheme

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

                    if(path == null) {
                        Text("No destination file", color = colors.onError, modifier = Modifier.background(colors.error).fillMaxWidth())
                    } else {
                        Text(path.substringAfterLast(':'), color = colors.onBackground, modifier = Modifier.fillMaxWidth())
                    }

                    Text("Saved : ${activity.numSaved.value}/${activity.translations.size}", color = colors.onBackground)

                    val numErr = activity.numErrors.value

                    Text("Errors: $numErr",
                        color = if(numErr == 0) colors.onBackground else colors.onError,
                        modifier = Modifier.background(if(numErr == 0) colors.background else colors.error)
                    )
                }

                Column() {
                    val BUTTON_WIDTH = 140.dp
                    val BUTTON_HEIGHT = 52.dp

                    CustomButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                setType("*/*")
                                addCategory(Intent.CATEGORY_OPENABLE)
                                putExtra(Intent.EXTRA_TITLE, "products.plu")
                                // FIXME: start in Documents folder
                            }

                            activity.setFileLauncher.launch(intent)
                        },
                        modifier = Modifier.size(BUTTON_WIDTH, BUTTON_HEIGHT)
                    ) {
                        Text(
                            "New File",
                            textAlign = TextAlign.Center
                        )
                    }

                    CustomButton(
                        onClick = {

                        },
                        modifier = Modifier.size(BUTTON_WIDTH, BUTTON_HEIGHT)
                    ) {
                        Text(
                            "Overwrite Existing File",
                            textAlign = TextAlign.Center
                        )
                    } // FIXME: implement
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().border(width = 2.dp, color = colors.onBackground),
                    state = lazyColumnState
                ) {
                    val lastIndex = activity.translations.lastIndex

                    if (lastIndex != savedIndex) {
                        coroutineScope.launch {
                            lazyColumnState.scrollToItem(Integer.max(0, lastIndex))
                        }

                        savedIndex = lastIndex
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