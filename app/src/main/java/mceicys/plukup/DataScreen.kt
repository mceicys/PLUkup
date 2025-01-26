package mceicys.plukup

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumTouchTargetEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.math.MathUtils.clamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max

private val INPUT_ROW_HEIGHT = 40.dp
private val BUTTON_HEIGHT = 52.dp

private val NAME_KEYBOARD_OPTIONS = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Uri // Hack to disable autocorrect so it doesn't fix names like "Old Tyme"
    /* This decision could be left to the user's keyboard settings, but the user will be
    regularly writing unusual, incomplete keywords, and autocorrect gets in the way of that */
)

class DataScreen {
    fun reportClear() {
        openedProductID = -1
        requiredFlags = 0
        excludedFlags = 0
        coloredFlags = 0
    }

    fun reportDeletedFlag(flagIndex: Int) {
        requiredFlags = removeBits(requiredFlags, flagIndex, 1)
        excludedFlags = removeBits(excludedFlags, flagIndex, 1)
        coloredFlags = removeBits(coloredFlags, flagIndex, 1)
    }

    fun reportMovedFlag(source: Int, target: Int) {
        requiredFlags = moveBit(requiredFlags, source, target)
        excludedFlags = moveBit(excludedFlags, source, target)
        coloredFlags = moveBit(coloredFlags, source, target)
    }

    fun reportFlagColorsChange() {
        coloredFlags = 0

        for(i in productFlags.indices) {
            val f = productFlags[i]
            if(f.hue >= 0f) coloredFlags = coloredFlags or f.mask
        }
    }

    fun reportModifiedProduct(index: Int) {
        val modifiedProduct = products[index]

        if(openedProductID == modifiedProduct.id) {
            // Faster than else
            val subIndex = productSubset.indexOfFirst {product -> product.id == modifiedProduct.id}

            if (subIndex != -1) {
                productSubset[subIndex] = modifiedProduct
                recheckFilter(productSubset[subIndex])
            }
        } else {
            searchProducts()
        }
    }

    fun searchProducts() {
        searchJob?.cancel()
        searchJob = null

        productSubset.clear()
        searchKeywords = cleanedName(searchField.text).split(" ").filter{it.isNotEmpty()}

        // Use a coroutine for quicker feedback, to make typing more responsive
        searchJob = CoroutineScope(Dispatchers.Main).launch {
            products.forEach {
                if (productFilter(it))
                    productSubset.add(it)
            }
        }
    }

    // Removes product from productSubset if it no longer passes productFilter
    fun recheckFilter(product: Product) {
        val index = productSubset.indexOf(product)

        if(index == -1)
            return

        if(!productFilter(product))
            productSubset.removeAt(index)
    }

    fun scrollToID(idStr: String) {
        val id = idStr.toIntOrNull()
        var index = 0

        if(id != null) {
            index = productSubset.binarySearchBy(id, selector = {it.id})

            if(index < 0) {
                index = max(0, -index - 2) // Scroll to product before insertion index
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            productListState.scrollToItem(index)
        }
    }

    @Composable
    fun DataScreenUI(onNavigateToRecorder: () -> Unit) {
        val context = LocalContext.current
        val act = context as MainActivity
        val scheme = MaterialTheme.colorScheme
        val nightMode = context.resources.configuration.isNightModeActive
        val colors = ColorSet(scheme.primary, scheme.secondary, scheme.background)
        val aliasColors = ColorSet(120f, nightMode)

        SidePaddingParent {
            ProductListUI(act, onNavigateToRecorder, colors, aliasColors, nightMode)

            if (showIDPrompt) {
                IDPromptUI(colors)
            }

            if (showEditAliasPrompt) {
                EditAliasPromptUI(act, aliasColors)
            }

            if (showDeleteAliasPrompt) {
                DeleteAliasPromptUI(act, aliasColors)
            }

            if (showSettingsMenu) {
                SettingsMenuUI(act, colors)
            }

            if (showNewFilePrompt) {
                NewFilePromptUI(act, colors)
            }

            if (showFlagMenu) {
                FlagsMenuUI(act, colors, nightMode)
            }

            if (showEditFlagPrompt) {
                EditFlagPromptUI(act, colors, nightMode)
            }

            if (showDeleteFlagPrompt) {
                DeleteFlagPromptUI(act, colors)
            }

            if (showFilterMenu) {
                FilterMenuUI(colors, nightMode)
            }
        }
    }

    /*
    PRIVATE
    */

    private val productSubset = mutableStateListOf<Product>()
    private var searchField by mutableStateOf(TextFieldValue())
    private var searchKeywords = List(0) { "" }
    private var searchJob: Job? = null
    private var productListState by mutableStateOf(LazyListState())
    private var openedProductID by mutableIntStateOf(-1)
    private var requiredFlags by mutableIntStateOf(0)
    private var excludedFlags by mutableIntStateOf(0)
    private var coloredFlags by mutableIntStateOf(0)

    // Returns true if product should be included in productSubset
    private fun productFilter(product: Product) : Boolean {
        if(product.id == openedProductID) // Don't hide an opened product
            return true

        if(product.flags and excludedFlags != 0)
            return false

        if(product.flags and requiredFlags != requiredFlags)
            return false

        // Lenient filtering: each search keyword must show up somewhere in the list of aliases
        // This lets aliases effectively "add" keywords to the original name
        return matchingName(searchKeywords, product.compositeName.cleaned, true)

        /*
        // Strict filtering: all search keywords must show up in a single alias
            // Each alias acts as a name distinct from the original
        if(matchingName(searchKeywords, product.name.cleaned, true))
            return true

        if (product.aliases != null) {
            for (alias in product.aliases) {
                if(matchingName(searchKeywords, alias.cleaned, true))
                    return true
            }
        }

        return false
        */
    }

    private fun matchingName(keywords: List<String>, name: String, ignoreCase: Boolean) : Boolean {
        for(keyword in keywords) {
            if(!name.contains(keyword, ignoreCase)) {
                return false
            }
        }

        return true
    }

    private fun resolveProductHue(product: Product) : Float {
        val coloredProductFlags = product.flags and coloredFlags

        if(coloredProductFlags != 0) {
            return productFlags[coloredProductFlags.countTrailingZeroBits()].hue
        }

        return -1f
    }

    /*
        PRIVATE UI
    */

    private var forceRecompose by mutableIntStateOf(0)
    private var editMode by mutableStateOf(false)

    private var showIDPrompt by mutableStateOf(false)

    private var showEditAliasPrompt by mutableStateOf(false)
    private var editAliasPromptProductID by mutableIntStateOf(-1)
    private var editAliasPromptIndex by mutableIntStateOf(-1)
    private var editAliasField by mutableStateOf(TextFieldValue())
    private var showDeleteAliasPrompt by mutableStateOf(false)

    private var showSettingsMenu by mutableStateOf(false)

    private var showNewFilePrompt by mutableStateOf(false)
    private var newFilePromptField by mutableStateOf(TextFieldValue())

    private var showFlagMenu by mutableStateOf(false)
    private val flagLazyColumnState by mutableStateOf(LazyListState())

    private var showEditFlagPrompt by mutableStateOf(false)
    private var editFlagPromptNameField by mutableStateOf(TextFieldValue())
    private var editFlagPromptHueField by mutableStateOf(TextFieldValue())
    private var editFlagPromptIndex by mutableIntStateOf(-1)
    private var showDeleteFlagPrompt by mutableStateOf(false)

    private var showFilterMenu by mutableStateOf(false)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ProductListUI(act: MainActivity, onNavigateToRecorder: () -> Unit, colors: ColorSet,
                              aliasColors: ColorSet, nightMode: Boolean) {
        Column(modifier = Modifier.background(colors.background)) {
            TopPadding()

            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                val asterisk = if(unsavedChanges) "*" else ""

                Text(
                    text = "${productSubset.size}/${products.size}; $asterisk${selectedWorkFile?.name}",
                    color = colors.onBackground,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Row {
                    CustomButton(
                        onClick = { showIDPrompt = true },
                        modifier = Modifier.size(64.dp, INPUT_ROW_HEIGHT)
                    ) {Text("ID")}

                    CustomTextField(
                        searchField,
                        onValueChange = { newField -> searchField = newField; searchProducts() },
                        placeholder = { Text("Name") },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Clear text",
                                modifier = Modifier.clickable { searchField = searchField.copy(""); searchProducts() })
                        },
                        keyboardOptions = NAME_KEYBOARD_OPTIONS,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(INPUT_ROW_HEIGHT)
                    )
                }
            }

            LazyColumn(modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .weight(1.0f),
                state = productListState) {
                itemsIndexed(productSubset) { index, product ->
                    val opened = openedProductID == product.id
                    val productHue = resolveProductHue(product)
                    val altColors = ColorSet(productHue, nightMode)
                    val productColors = if(productHue < 0f) colors else ColorSet(colors.primary, altColors.secondary, altColors.background)
                    val backColor = if (index % 2 == 0) productColors.background else productColors.secondary
                    val backColorAlias = if (index % 2 == 0) aliasColors.background else aliasColors.secondary
                    val borderIfOpen = if(opened) Modifier.border(1.dp, productColors.primary) else Modifier

                    Column(
                        modifier = borderIfOpen
                    ) {
                        CompositionLocalProvider(LocalMinimumTouchTargetEnforcement provides false) {
                            Surface(onClick = {
                                val save = openedProductID
                                openedProductID = if (opened) -1 else product.id
                                productFromID(save)?.let { recheckFilter(it) }
                            }) {
                                Column {
                                    Row(modifier = Modifier.background(backColor)) {
                                        Text(
                                            product.id.toString(),
                                            textAlign = TextAlign.Right,
                                            color = productColors.onBackground,
                                            modifier = Modifier
                                                .width(72.dp) // Should be enough space for 6 monospace digits
                                                .padding(end = 8.dp)
                                        )
                                        Text(
                                            product.name.original.trim(),
                                            color = productColors.onBackground,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    if (product.aliases != null) {
                                        for (i in product.aliases.indices) {
                                            val alias = product.aliases[i]

                                            Row(
                                                modifier = Modifier.background(backColorAlias),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                val showDeleteButton = editMode && opened

                                                if (showDeleteButton) {
                                                    CustomButton(
                                                        onLongClick = {
                                                            editAliasField =
                                                                TextFieldValue(alias.original)
                                                            editAliasPromptProductID =
                                                                product.id
                                                            editAliasPromptIndex = i
                                                            showEditAliasPrompt = true
                                                        },
                                                        borderColor = backColorAlias,
                                                        contentColor = backColorAlias,
                                                        backgroundColor = aliasColors.primary,
                                                        modifier = Modifier.size(72.dp, 40.dp)
                                                    ) { Text("Edit") }
                                                }

                                                val textPadding = if (showDeleteButton) 0.dp else 72.dp

                                                Text(
                                                    "-> " + alias.original.trim(),
                                                    modifier = Modifier
                                                        .padding(start = textPadding)
                                                        .fillMaxWidth(),
                                                    color = aliasColors.primary
                                                )
                                            }
                                        }
                                    }

                                    if (product.id == openedProductID && editMode) {
                                        Row(
                                            modifier = Modifier
                                                .background(backColorAlias)
                                                .fillMaxWidth()
                                        ) {
                                            CustomButton(
                                                onClick = {
                                                    editAliasField = editAliasField.copy("")
                                                    editAliasPromptProductID = product.id
                                                    editAliasPromptIndex = -1
                                                    showEditAliasPrompt = true
                                                },
                                                borderColor = aliasColors.primary,
                                                contentColor = aliasColors.primary,
                                                backgroundColor = backColorAlias,
                                                modifier = Modifier.size(72.dp, 40.dp)
                                            ) { Text("New") }
                                        }
                                    }
                                }
                            }

                            if (product.id == openedProductID) {
                                Row(modifier = Modifier
                                    .background(backColor)
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)) {
                                    Text(
                                        "Flags: ",
                                        color = productColors.onBackground,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 72.dp)
                                    )

                                    if(!editMode && product.flags == 0) {
                                        Text(
                                            "None",
                                            color = productColors.onBackground
                                        )
                                    }
                                }

                                if(editMode) {
                                    for (flag in productFlags) {
                                        Surface(onClick = {
                                            act.modifyProduct(product, product.flags xor flag.mask)
                                        }) {
                                            Row(
                                                modifier = Modifier
                                                    .background(backColor)
                                                    .fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = (product.flags and flag.mask) != 0,
                                                    onCheckedChange = null,
                                                    modifier = Modifier.padding(start = 72.dp, top = 8.dp, bottom = 8.dp)
                                                )

                                                Text(
                                                    flag.name,
                                                    color = productColors.onBackground,
                                                    modifier = Modifier.padding(start = 8.dp)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    for (flag in productFlags) {
                                        if(product.flags and flag.mask != 0) {
                                            Row(
                                                modifier = Modifier
                                                    .background(backColor)
                                                    .fillMaxWidth()
                                            ) {
                                                Text(
                                                    flag.name,
                                                    color = productColors.onBackground,
                                                    modifier = Modifier.padding(start = 72.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(modifier = Modifier
                                    .background(backColor)
                                    .fillMaxWidth()
                                    .height(16.dp)) {}
                            }
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val bottomButtonWidth = LocalConfiguration.current.screenWidthDp.dp / 3

                CustomButton(
                    onClick = {showSettingsMenu = true},
                    modifier = Modifier.size(bottomButtonWidth, BUTTON_HEIGHT)
                ) { Text("Settings", textAlign = TextAlign.Center) }

                CustomButton(
                    onClick = { showFilterMenu = true },
                    modifier = Modifier.size(bottomButtonWidth, BUTTON_HEIGHT)
                ) { Text("Filter", textAlign = TextAlign.Center) }

                CustomButton(
                    onClick = onNavigateToRecorder,
                    modifier = Modifier.size(bottomButtonWidth, BUTTON_HEIGHT)
                ) { Text("Recorder", textAlign = TextAlign.Center) }
            }

            BottomPadding(BUTTON_HEIGHT)
        }
    }

    @Composable
    private fun IDPromptUI(colors: ColorSet) {
        var idField by remember { mutableStateOf(TextFieldValue()) }
        val idFocusRequester = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        val dismiss = {showIDPrompt = false}

        Dialog(
            onDismissRequest = dismiss
        ) {
            Card(
                shape = RectangleShape,
                colors = CardDefaults.cardColors(
                    containerColor = colors.background,
                    contentColor = colors.primary
                )
            ) {
                CustomTextField(
                    idField,
                    onValueChange = { newField -> if(newField.text.length <= 9) idField = newField },
                    placeholder = { Text("ID #") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            scrollToID(idField.text)
                            dismiss()
                        }
                    ),
                    modifier = Modifier
                        .focusRequester(idFocusRequester)
                        .size(108.dp, INPUT_ROW_HEIGHT)
                )
            }

            LaunchedEffect(showIDPrompt) {
                if(showIDPrompt) {
                    idField = idField.copy("")
                    focusManager.clearFocus(true)
                    idFocusRequester.requestFocus()
                }
            }
        }
    }

    @Composable
    private fun EditAliasPromptUI(act: MainActivity, aliasColors: ColorSet) {
        GeneralNamePrompt(
            submit = {act.setProductAlias(editAliasPromptProductID, editAliasPromptIndex, editAliasField.text)},
            dismiss = {showEditAliasPrompt = false},
            delete = if(editAliasPromptIndex >= 0) ({showDeleteAliasPrompt = true}) else null,
            deleteEllipsis = true,
            upperRightText = productFromID(editAliasPromptProductID)?.name?.original?.trim(),
            show = showEditAliasPrompt,
            field = editAliasField,
            onFieldChange = { new -> editAliasField = new },
            fieldPlaceholder = "Alias",
            primary = aliasColors.primary,
            secondary = aliasColors.secondary,
            background = aliasColors.background
        )
    }

    @Composable
    private fun DeleteAliasPromptUI(act: MainActivity, aliasColors: ColorSet) {
        val product = productFromID(editAliasPromptProductID)
        val name = product?.name?.original?.trim()
        val alias = product?.aliases?.getOrNull(editAliasPromptIndex)?.original

        GeneralDeletePrompt(
            delete = {
                act.deleteProductAlias(editAliasPromptProductID, editAliasPromptIndex)
                showEditAliasPrompt = false
            },
            dismiss = { showDeleteAliasPrompt = false },
            promptText = "Delete alias '$alias'?",
            upperRightText = name,
            primary = aliasColors.primary,
            background = aliasColors.background
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsMenuUI(act: MainActivity, colors: ColorSet) {
        var backupHoursField by remember { mutableStateOf(TextFieldValue()) }
        var backupMinutesField by remember { mutableStateOf(TextFieldValue()) }
        var maxNumBackupsField by remember { mutableStateOf(TextFieldValue()) }

        val dismiss = { showSettingsMenu = false }

        Dialog(
            onDismissRequest = dismiss
        ) {
            Card(
                shape = RectangleShape,
                colors = CardDefaults.cardColors(
                    containerColor = colors.background,
                    contentColor = colors.primary
                ),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Box(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Column {
                        Row(modifier = Modifier.align(Alignment.End)) {
                            Text("SETTINGS")
                        }

                        Row(modifier = Modifier.padding(bottom = 8.dp)) {
                            Surface(onClick = { editMode = !editMode }) {
                                Row(
                                    modifier = Modifier.background(colors.background).fillMaxWidth()
                                ) {
                                    Checkbox(
                                        editMode,
                                        onCheckedChange = null,
                                        modifier = Modifier.padding(8.dp)
                                    )

                                    Text(
                                        "Edit Mode",
                                        color = colors.primary,
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    )
                                }
                            }
                        }

                        Row(modifier = Modifier.padding(bottom = 8.dp)) {
                            Column(modifier = Modifier.weight(0.65f)) {
                                Row {
                                    Text("Work Directory:", fontWeight = FontWeight.Bold)
                                }

                                Row {
                                    Text(uriStringSimplePath(workDirectory))
                                }
                            }

                            Column(modifier = Modifier.weight(0.35f)) {
                                CustomButton(
                                    onLongClick = {
                                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                            flags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                        }

                                        act.chooseDirectoryLauncher.launch(intent)
                                    },
                                    borderColor = colors.background,
                                    contentColor = colors.background,
                                    backgroundColor = colors.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(BUTTON_HEIGHT)
                                ) {
                                    Text(
                                        "Change",
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        Row(modifier = Modifier.padding(bottom = 16.dp)) {
                            var expanded by remember { mutableStateOf(false) }

                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = {expanded = !expanded}
                            ) {
                                CustomTextField(
                                    value = TextFieldValue(selectedWorkFile?.name ?: ""),
                                    onValueChange = {},
                                    readOnly = true,
                                    placeholder = {Text("No file selected")},
                                    trailingIcon = {ExposedDropdownMenuDefaults.TrailingIcon(expanded)},
                                    modifier = Modifier.menuAnchor(),
                                    innerTopPadding = 14.dp // HACK: Manual vertical center
                                )

                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("<Close File>") },
                                        onClick = {
                                            act.selectWorkFile(null, true)
                                            expanded = false
                                        }
                                    )

                                    act.workFiles()?.forEach { doc ->
                                        val name = doc.name ?: ""

                                        if(name.endsWith(".plu", true)) {
                                            DropdownMenuItem(
                                                text = { Text(name) },
                                                onClick = {
                                                    act.selectWorkFile(doc, true)
                                                    expanded = false
                                                }
                                            )
                                        }
                                    }

                                    DropdownMenuItem(
                                        text = { Text("<New File>") },
                                        onClick = {
                                            showNewFilePrompt = true
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Row {
                            Text("Backups", fontWeight = FontWeight.Bold)
                        }

                        Row {
                            Text("Allowed Backup Frequency")
                        }

                        Row(modifier = Modifier.padding(bottom = 8.dp)) {
                            Column {
                                Text("Hours")
                                CustomTextField(
                                    backupHoursField,
                                    onValueChange = {
                                            newField -> backupHoursField = newField
                                        act.setBackupPeriodHours(backupHoursField.text.toIntOrNull() ?: 0)
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.padding(end = 8.dp).size(80.dp, INPUT_ROW_HEIGHT)
                                )
                            }

                            Column {
                                Text("Minutes")
                                CustomTextField(
                                    backupMinutesField,
                                    onValueChange = {
                                            newField -> backupMinutesField = newField
                                        act.setBackupPeriodMinutes(backupMinutesField.text.toIntOrNull() ?: 0)
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.size(80.dp, INPUT_ROW_HEIGHT)
                                )
                            }
                        }

                        Row(modifier = Modifier.padding(bottom = 16.dp)) {
                            Column {
                                Text("Maximum Number of Backups")
                                CustomTextField(
                                    maxNumBackupsField,
                                    onValueChange = {
                                            newField -> maxNumBackupsField = newField
                                        act.setMaxNumBackups(maxNumBackupsField.text.toIntOrNull() ?: 0)
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.size(80.dp, INPUT_ROW_HEIGHT)
                                )
                            }
                        }

                        Row {
                            CustomButton(
                                onClick = { showFlagMenu = true },
                                modifier = Modifier.size(120.dp, BUTTON_HEIGHT)
                            ) { Text("Flags", textAlign = TextAlign.Center) }
                        }
                    }
                }
            }
        }

        LaunchedEffect(showSettingsMenu) {
            backupHoursField = backupHoursField.copy(backupPeriodHours.toString())
            backupMinutesField = backupMinutesField.copy(backupPeriodMinutes.toString())
            maxNumBackupsField = maxNumBackupsField.copy(maxNumBackups.toString())
        }
    }

    @Composable
    private fun NewFilePromptUI(act: MainActivity, colors: ColorSet) {
        GeneralNamePrompt(
            submit = { act.createEmptyWorkFile(newFilePromptField.text) },
            dismiss = {showNewFilePrompt = false},
            upperRightText = "New File",
            show = showNewFilePrompt,
            field = newFilePromptField,
            onFieldChange = { new -> newFilePromptField = new },
            fieldPlaceholder = "File Name",
            primary = colors.primary,
            secondary = colors.secondary,
            background = colors.background,
            onShow = { newFilePromptField = newFilePromptField.copy("") }
        )
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun FlagsMenuUI(act: MainActivity, colors: ColorSet, nightMode: Boolean) {
        val dismiss = {
            showFlagMenu = false
            searchProducts()
        }

        Dialog(
            onDismissRequest = dismiss
        ) {
            Card(
                shape = RectangleShape,
                colors = CardDefaults.cardColors(
                    containerColor = colors.background,
                    contentColor = colors.primary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier.padding(2.dp)
                    ) {
                        Text(
                            "Flags ${productFlags.size}/$MAX_PRODUCT_FLAGS",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    key(forceRecompose) {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .fillMaxWidth(),
                            state = flagLazyColumnState
                        )
                        {
                            itemsIndexed(productFlags) { index, flag ->
                                val flagColors = if(flag.hue < 0f) colors else ColorSet(flag.hue, nightMode)
                                val backColor = if (index % 2 == 0) flagColors.background else flagColors.secondary

                                val dragCallback = remember {
                                    object : DragAndDropTarget {
                                        override fun onDrop(event: DragAndDropEvent): Boolean {
                                            val sourceIndex =
                                                event.toAndroidDragEvent().clipData.getItemAt(0).text?.toString()
                                                    ?.toIntOrNull()

                                            if (sourceIndex != null) {
                                                act.moveFlag(sourceIndex, index)
                                            }

                                            forceRecompose++ // To show new order
                                            return true
                                        }
                                    }
                                }

                                val dragModifier = if (editMode) {
                                    Modifier
                                        .dragAndDropSource {
                                            detectTapGestures(onLongPress = {
                                                startTransfer(
                                                    DragAndDropTransferData(
                                                        ClipData.newPlainText(
                                                            "Flag Index",
                                                            index.toString()
                                                        )
                                                    )
                                                )
                                            })
                                        }
                                        .dragAndDropTarget(
                                            shouldStartDragAndDrop = { true },
                                            target = dragCallback
                                        )
                                } else Modifier

                                Surface(modifier = dragModifier) {
                                    Row(
                                        modifier = Modifier
                                            .background(backColor)
                                            .padding(8.dp)
                                            .fillMaxWidth()
                                    ) {
                                        if (editMode) {
                                            CustomButton(
                                                onLongClick = {
                                                    editFlagPromptIndex = index
                                                    editFlagPromptNameField =
                                                        TextFieldValue(flag.name)
                                                    editFlagPromptHueField =
                                                        TextFieldValue(flag.hue.toString())
                                                    showEditFlagPrompt = true
                                                },
                                                borderColor = backColor,
                                                contentColor = backColor,
                                                backgroundColor = colors.primary,
                                                modifier = Modifier.size(72.dp, 40.dp)
                                            ) { Text("Edit") }
                                        }

                                        Text(
                                            "${index + 1}. ${flag.name}",
                                            color = colors.onBackground,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if(productFlags.size < MAX_PRODUCT_FLAGS && editMode) {
                        Row(
                            modifier = Modifier.padding(8.dp)
                        ) {
                            CustomButton(
                                onClick = {
                                    editFlagPromptIndex = -1
                                    editFlagPromptNameField = editFlagPromptNameField.copy("")
                                    editFlagPromptHueField = editFlagPromptHueField.copy("")
                                    showEditFlagPrompt = true
                                },
                                modifier = Modifier.size(120.dp, BUTTON_HEIGHT)
                            ) { Text("New Flag", style = MaterialTheme.typography.bodyLarge) }
                        }
                    }

                    CustomButton(
                        onClick = dismiss,
                        modifier = Modifier
                            .height(BUTTON_HEIGHT)
                            .fillMaxWidth()
                    ) {Text("Back", style = MaterialTheme.typography.bodyLarge)}
                }
            }
        }
    }

    @Composable
    private fun EditFlagPromptUI(act: MainActivity, colors: ColorSet, nightMode: Boolean) {
        val coroutineScope = rememberCoroutineScope()

        GeneralNamePrompt(
            submit = {
                val hue = editFlagPromptHueField.text.toFloatOrNull() ?: -1f

                if(editFlagPromptIndex < 0) {
                    act.addFlag(editFlagPromptNameField.text, hue)

                    coroutineScope.launch {
                        flagLazyColumnState.scrollToItem(productFlags.size - 1)
                    }
                } else {
                    act.modifyFlag(editFlagPromptIndex, editFlagPromptNameField.text, hue)
                    forceRecompose++ // To show hue change
                }
            },
            dismiss = {showEditFlagPrompt = false},
            delete = if(editFlagPromptIndex >= 0) ({showDeleteFlagPrompt = true}) else null,
            deleteEllipsis = true,
            upperRightText = (
                    if(editFlagPromptIndex < 0)
                        "New Flag"
                    else
                        productFlags.getOrNull(editFlagPromptIndex)?.name ?: "??"
                    ),
            show = showEditFlagPrompt,
            field = editFlagPromptNameField,
            onFieldChange = { new -> editFlagPromptNameField = new },
            fieldPlaceholder = "Flag Name",
            primary = colors.primary,
            secondary = colors.secondary,
            background = colors.background,
            extraOptions = {
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    val backHue = editFlagPromptHueField.text.toFloatOrNull() ?: -1f
                    val backColor = if(backHue < 0f) colors.background else ColorSet(backHue, nightMode).background

                    CustomTextField(
                        editFlagPromptHueField,
                        onValueChange = { newField -> editFlagPromptHueField = newField },
                        placeholder = { Text("Hue 0-360") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        modifier = Modifier.size(108.dp, INPUT_ROW_HEIGHT),
                        backgroundColor = backColor
                    )
                }
            }
        )
    }

    @Composable
    private fun DeleteFlagPromptUI(act: MainActivity, colors: ColorSet) {
        val name = productFlags.getOrNull(editFlagPromptIndex)?.name

        GeneralDeletePrompt(
            delete = {
                act.deleteFlag(editFlagPromptIndex)
                forceRecompose++ // To stop the wrong flag from remaining visible in the list
                showEditFlagPrompt = false
            },
            dismiss = {showDeleteFlagPrompt = false},
            promptText = "Delete flag '$name'?",
            upperRightText = null,
            primary = colors.primary,
            background = colors.background
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun FilterMenuUI(colors: ColorSet, nightMode: Boolean) {
        /* FIXME: Filter card is not resized when orientation changes because
                android:configChanges="orientation|screenSize" is set in the manifest */

        val dismiss = {
            showFilterMenu = false
            searchProducts()
        }

        Dialog(
            onDismissRequest = dismiss
        ) {
            Card(
                shape = RectangleShape,
                colors = CardDefaults.cardColors(
                    containerColor = colors.background,
                    contentColor = colors.primary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .weight(1f, fill = false)
                    ) {
                        for (flag in productFlags) {
                            val UNFILTERED = 0
                            val REQUIRED = 1
                            val EXCLUDED = 2

                            val flagMode = if (flag.mask and requiredFlags != 0) {
                                REQUIRED
                            } else if (flag.mask and excludedFlags != 0) {
                                EXCLUDED
                            } else {
                                UNFILTERED
                            }

                            CompositionLocalProvider(LocalMinimumTouchTargetEnforcement provides false) {
                                Surface(
                                    onClick = {
                                        when (flagMode) {
                                            UNFILTERED -> { // Shift from unfiltered to required
                                                requiredFlags =
                                                    requiredFlags or flag.mask
                                                excludedFlags =
                                                    excludedFlags and flag.mask.inv()
                                            }

                                            REQUIRED -> { // Shift from required to excluded
                                                requiredFlags =
                                                    requiredFlags and flag.mask.inv()
                                                excludedFlags =
                                                    excludedFlags or flag.mask
                                            }

                                            EXCLUDED -> { // Shift from excluded to unfiltered
                                                requiredFlags =
                                                    requiredFlags and flag.mask.inv()
                                                excludedFlags =
                                                    excludedFlags and flag.mask.inv()
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val flagColors = if(flag.hue < 0f) colors else ColorSet(flag.hue, nightMode)

                                    Row(
                                        modifier = Modifier
                                            .background(flagColors.background)
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            when (flagMode) {
                                                UNFILTERED -> "-------"
                                                REQUIRED -> "REQUIRE"
                                                EXCLUDED -> "EXCLUDE"
                                                else -> "???????"
                                            },
                                            color = colors.onBackground,
                                            modifier = Modifier.background(flagColors.secondary)
                                        )

                                        Text(
                                            flag.name,
                                            color = colors.onBackground,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.padding(8.dp).fillMaxWidth()
                    ) {
                        CustomButton(
                            onClick = dismiss,
                            modifier = Modifier
                                .size(160.dp, BUTTON_HEIGHT)
                                .padding(end = 8.dp)
                        ) { Text("Set Filters", style = MaterialTheme.typography.bodyLarge) }

                        CustomButton(
                            onLongClick = { requiredFlags = 0; excludedFlags = 0 },
                            modifier = Modifier.size(100.dp, BUTTON_HEIGHT),
                            borderColor = colors.background,
                            contentColor = colors.background,
                            backgroundColor = colors.primary
                        ) { Text("Clear", style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralNamePrompt(
    submit: () -> Unit,
    dismiss: () -> Unit,
    delete: (() -> Unit)? = null,
    deleteEllipsis: Boolean = false,
    upperRightText: String?,
    show: Boolean,
    field: TextFieldValue,
    onFieldChange: (new: TextFieldValue) -> Unit,
    fieldPlaceholder: String,
    primary: Color,
    secondary: Color,
    background: Color,
    extraOptions: @Composable (() -> Unit)? = null,
    onShow: (suspend CoroutineScope.() -> Unit)? = null
) {
    val promptButtonWidth = 100.dp
    val focusManager = LocalFocusManager.current
    val fieldFocusRequester = remember { FocusRequester() }

    Dialog(
        onDismissRequest = dismiss
    ) {
        Card(
            shape = RectangleShape,
            colors = CardDefaults.cardColors(
                containerColor = background,
                contentColor = primary
            )
        ) {
            Box(modifier = Modifier.padding(4.dp)) {
                Column {
                    if(upperRightText != null) {
                        Text(
                            upperRightText,
                            color = primary,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .padding(bottom = 20.dp)
                                .fillMaxWidth()
                        )
                    }

                    CustomTextField(
                        field,
                        onValueChange = onFieldChange,
                        placeholder = { Text(fieldPlaceholder) },
                        singleLine = true,
                        keyboardOptions = NAME_KEYBOARD_OPTIONS,
                        keyboardActions = KeyboardActions(
                            onDone = {
                                submit()
                                dismiss()
                            }
                        ),
                        modifier = Modifier
                            .focusRequester(fieldFocusRequester)
                            .height(INPUT_ROW_HEIGHT)
                            .fillMaxWidth(),
                        primaryColor = primary,
                        secondaryColor = secondary,
                        backgroundColor = background
                    )

                    if(extraOptions != null)
                        extraOptions()

                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp)
                    ) {
                        if(delete != null) {
                            CustomButton(
                                onLongClick = {
                                    delete()
                                },
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(promptButtonWidth, BUTTON_HEIGHT),
                                borderColor = background,
                                contentColor = background,
                                backgroundColor = primary
                            ) {Text(if(deleteEllipsis) "Delete..." else "Delete", textAlign = TextAlign.Center)}
                        }

                        CustomButton(
                            onClick = { dismiss() },
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(promptButtonWidth, BUTTON_HEIGHT),
                            borderColor = primary,
                            contentColor = primary,
                            backgroundColor = background,
                        ) { Text("Cancel", textAlign = TextAlign.Center) }

                        CustomButton(
                            onClick = {
                                submit()
                                dismiss()
                            },
                            modifier = Modifier.size(promptButtonWidth, BUTTON_HEIGHT),
                            borderColor = primary,
                            contentColor = primary,
                            backgroundColor = background
                        ) { Text("Set", textAlign = TextAlign.Center) }
                    }
                }
            }
        }

        LaunchedEffect(show) {
            if(show) {
                focusManager.clearFocus(true)
                fieldFocusRequester.requestFocus()
                onFieldChange(field.copy(field.text, TextRange(0, field.text.length)))

                if(onShow != null)
                    onShow()
            }
        }
    }
}

@Composable
private fun GeneralDeletePrompt(
    delete: () -> Unit,
    dismiss: () -> Unit,
    promptText: String?,
    upperRightText: String?,
    primary: Color,
    background: Color
) {
    val promptButtonWidth = 100.dp

    Dialog(
        onDismissRequest = dismiss
    ) {
        Card(
            shape = RectangleShape,
            colors = CardDefaults.cardColors(
                containerColor = background,
                contentColor = primary
            )
        ) {
            Box(modifier = Modifier.padding(4.dp)) {
                Column {
                    if(upperRightText != null) {
                        Text(
                            upperRightText,
                            color = primary,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .padding(bottom = 20.dp)
                                .fillMaxWidth()
                        )
                    }

                    if(promptText != null) {
                        Text(
                            promptText,
                            color = primary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp)
                    ) {
                        CustomButton(
                            onClick = dismiss,
                            borderColor = primary,
                            contentColor = primary,
                            backgroundColor = background,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(promptButtonWidth, BUTTON_HEIGHT)
                        ) { Text("Cancel", style = MaterialTheme.typography.bodyLarge) }

                        CustomButton(
                            onLongClick = {
                                delete()
                                dismiss()
                            },
                            borderColor = background,
                            contentColor = background,
                            backgroundColor = primary,
                            modifier = Modifier.size(promptButtonWidth, BUTTON_HEIGHT)
                        ) { Text("Delete", style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        }
    }
}

private class ColorSet {
    val primary: Color
    val secondary: Color
    val background: Color

    val onBackground: Color
        get() {return primary}

    constructor(primary: Color, secondary: Color, background: Color) {
        this.primary = primary
        this.secondary = secondary
        this.background = background
    }

    constructor(hue: Float, nightMode: Boolean) {
        val clampedHue = clamp(hue, 0.0f, 360.0f)
        val light = Color.hsl(clampedHue, 0.7f, 0.85f)
        val medium = Color.hsl(clampedHue, 0.6f, 0.77f)
        val mediumDark = Color.hsl(clampedHue, 0.4f, 0.38f)
        val dark = Color.hsl(clampedHue, 0.5f, 0.3f)

        this.primary = if(nightMode) light else dark
        this.secondary = if(nightMode) mediumDark else medium
        this.background = if(nightMode) dark else light
    }
}

private fun uriStringNameOnly(uri: Uri?) : String {
    return uri?.path?.substringAfterLast('/') ?: ""
}

private fun uriStringSimplePath(uri: Uri?) : String {
    return uri?.path?.substringAfterLast(':') ?: ""
}