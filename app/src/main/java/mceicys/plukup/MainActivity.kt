package mceicys.plukup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mceicys.plukup.ui.theme.PLUkupTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.max
import androidx.core.view.WindowCompat
import java.io.FileNotFoundException

val products = mutableListOf<Product>()
val productSubset = mutableStateListOf<Product>()
val searchString = mutableStateOf("")
val currentFileName = mutableStateOf("")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false) // To make WindowInsets work

        if(contentResolver.persistedUriPermissions.isNotEmpty()) {
            readProductsFromFile(contentResolver.persistedUriPermissions[0].uri)
        }

        setContent {
            PLUkupTheme {
                ProductListUI()
            }
        }
    }

    val openFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if(result.resultCode == Activity.RESULT_OK) {
            val intent = result.data
            val uri = intent?.data

            if(uri != null) {
                contentResolver.persistedUriPermissions.forEach {
                    contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                readProductsFromFile(uri)
            }
        }
    }

    fun readProductsFromFile(uri: Uri) {
        try {
            val stream = contentResolver.openInputStream(uri)

            if(stream != null) {
                currentFileName.value = uri.path?.substringAfterLast('/') ?: ""
                products.clear()
                products.addAll(parseProducts(stream.readBytes()))
                searchString.value = ""
                searchProducts("")
                stream.close()
            } else {
                Toast.makeText(this, "ERROR: Could not open file", Toast.LENGTH_LONG).show()
            }
        } catch(e: FileNotFoundException) {
            Toast.makeText(this, "ERROR: File not found", Toast.LENGTH_LONG).show()
            return
        }
    }

    fun forgetFile() {
        currentFileName.value = ""
        products.clear()

        contentResolver.persistedUriPermissions.forEach {
            contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        searchProducts("")
    }
}

// FIXME: run in a coroutine so typing doesn't feel as slow
fun searchProducts(str: String) {
    productSubset.clear()
    val keywords = cleanedName(str).split(" ").filter{it.isNotEmpty()}

    if(keywords.isEmpty()) {
        productSubset.addAll(products)
        return
    }

    products.forEach {
        var good = true

        for(keyword in keywords) {
            if(!it.cleaned.contains(keyword, true)) {
                good = false
                break
            }
        }

        if(good)
            productSubset.add(it)
    }
}

fun setSearchString(str: String) {
    searchString.value = str
    searchProducts(searchString.value)
}

@Composable
fun ProductListUI() {
    val context = LocalContext.current
    val activity = context as MainActivity
    val colors = MaterialTheme.colorScheme
        // FIXME: Figure out auto-coloring (e.g. Text should get onBackground color when in a Surface with background color)
    val showForgetPrompt = remember { mutableStateOf(false) }

    Column(modifier = Modifier.background(colors.background)) {
        // Top padding to make room for system UI since it's no longer resizing for us
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(
                WindowInsets.systemBars
                    .asPaddingValues()
                    .calculateTopPadding()
            )) {}

        Column(
            Modifier
                .fillMaxWidth()
                .padding(8.dp)) {
            Text("File: ${currentFileName.value}", color = colors.onBackground, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))

            CustomTextField(
                searchString.value,
                onValueChange = { txt -> setSearchString(txt) },
                placeholder = { Text("Search") },
                trailingIcon = {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Clear text",
                        modifier = Modifier.clickable { setSearchString("") })
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Uri // Hack to disable autocorrect so it doesn't fix names like "Old Tyme"
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        LazyColumn(modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .weight(1.0f)) {
            itemsIndexed(productSubset) { index, it ->
                Row(modifier = Modifier.background(if(index % 2 == 0) colors.background else colors.secondary)) {
                    Text(
                        it.id.toString(),
                        textAlign = TextAlign.Right,
                        color = colors.onBackground,
                        modifier = Modifier
                            //.width(textMeasurer.measure("999999", MaterialTheme.typography.bodyLarge).size.width.dp)
                            .width(72.dp) // Should be enough space for 6 monospace digits
                            .padding(end = 8.dp)
                    )
                    Text(
                        it.name.trimEnd(),
                        color = colors.onBackground,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        //val buttonWidth = 140.dp
        val buttonHeight = 52.dp

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val bottomButtonWidth = LocalConfiguration.current.screenWidthDp.dp / 3

            CustomButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "*/*"
                        flags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }

                    activity.openFileLauncher.launch(intent)
                },
                modifier = Modifier.size(bottomButtonWidth, buttonHeight)
            ) { Text("Open", textAlign = TextAlign.Center) }

            CustomButton(
                onClick = {
                    showForgetPrompt.value = true
                },
                modifier = Modifier.size(bottomButtonWidth, buttonHeight)
            ) { Text("Close", textAlign = TextAlign.Center) }

            if(showForgetPrompt.value) {
                val dismiss = {showForgetPrompt.value = false}
                val promptButtonWidth = 80.dp

                AlertDialog(
                    text = {Text("Close file?", color = colors.onBackground, style = MaterialTheme.typography.bodyLarge)},
                    containerColor = colors.background,
                    shape = RectangleShape,
                    onDismissRequest = dismiss,
                    confirmButton = {
                        CustomButton(
                            onClick = {
                                showForgetPrompt.value = false
                                activity.forgetFile()
                            },
                            modifier = Modifier.size(promptButtonWidth, buttonHeight)
                        ) { Text("Yes", style = MaterialTheme.typography.bodyLarge) }
                    },
                    dismissButton = {
                        CustomButton(
                            onClick = dismiss,
                            modifier = Modifier.size(promptButtonWidth, buttonHeight)
                        ) { Text("No", style = MaterialTheme.typography.bodyLarge) }
                    }
                )
            }

            CustomButton(
                onClick = {
                    val intent = Intent("mceicys.plukup.RECORDER")
                    context.startActivity(intent)
                },
                modifier = Modifier.size(bottomButtonWidth, buttonHeight)
            ) { Text("Recorder", textAlign = TextAlign.Center) }
        }

        // Bottom padding
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(
                max(
                    WindowInsets.systemBars
                        .asPaddingValues()
                        .calculateBottomPadding(),
                    WindowInsets.ime
                        .asPaddingValues()
                        .calculateBottomPadding() - buttonHeight
                )
            )) {}
    }
}