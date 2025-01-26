package mceicys.plukup

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import mceicys.plukup.ui.theme.PLUkupTheme
import java.io.FileNotFoundException
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.max
import kotlin.math.min

/* These globals are primarily controlled by MainActivity but they survive so long as the process
does, even if MainActivity is destroyed, so SaveService can always access them. */
var workDirectory by mutableStateOf<Uri?>(null)
    private set
var selectedWorkFile by mutableStateOf<DocumentFile?>(null)
    private set
private val mutableProducts = mutableListOf<Product>()
val products: List<Product> = mutableProducts
private val mutableProductFlags = mutableStateListOf<ProductFlag>()
val productFlags: List<ProductFlag> = mutableProductFlags
var unsavedChanges by mutableStateOf(false)
    private set
var backupPeriodMinutes by mutableIntStateOf(0)
    private set
var backupPeriodHours by mutableIntStateOf(6)
    private set
var maxNumBackups by mutableIntStateOf(5)
    private set

const val MAX_PRODUCT_FLAGS = Int.SIZE_BITS

private const val ERROR_BAD_PRODUCT_ID = "ERROR: No product with that ID"
private const val ERROR_BAD_ALIAS_INDEX = "ERROR: Alias index out of range"
private const val ERROR_BAD_FLAG_INDEX = "ERROR: Flag index is invalid"
private const val GENERIC_MIME = "*/*"
private const val WORK_FILE_PREF_KEY = "workFile"
private const val BACKUP_PERIOD_MINUTES_PREF_KEY = "backupPeriodMinutes"
private const val BACKUP_PERIOD_HOURS_PREF_KEY = "backupPeriodHours"
private const val MAX_NUM_BACKUPS_PREF_KEY = "maxNumBackups"
private const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"
private const val NOTIFICATION_CHANNEL_ID = "DEFAULT_CHANNEL"

private val BACKUP_TIME_FORMATTER : DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss_z")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false) // To make WindowInsets work

        if(checkSelfPermission(NOTIFICATION_PERMISSION) != PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(NOTIFICATION_PERMISSION)
        }

        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Default Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        if(contentResolver.persistedUriPermissions.isNotEmpty()) {
            setWorkDirectory(contentResolver.persistedUriPermissions[0].uri, false)
        }

        val prefs = getPreferences(MODE_PRIVATE)
        val savedWorkFile = prefs.getString(WORK_FILE_PREF_KEY, "")

        if(!savedWorkFile.isNullOrEmpty()) {
            selectWorkFile(workFiles()?.find { it.name == savedWorkFile }, false)
        }

        backupPeriodMinutes = prefs.getInt(BACKUP_PERIOD_MINUTES_PREF_KEY, backupPeriodMinutes)
        backupPeriodHours = prefs.getInt(BACKUP_PERIOD_HOURS_PREF_KEY, backupPeriodHours)
        maxNumBackups = prefs.getInt(MAX_NUM_BACKUPS_PREF_KEY, maxNumBackups)

        recorderScreen.onCreate()

        setContent {
            PLUkupTheme {
                PLUkupNavHost()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveChangesInService()
    }

    override fun onDestroy() {
        super.onDestroy()
        recorderScreen.onDestroy()
    }

    val chooseDirectoryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if(result.resultCode == Activity.RESULT_OK) {
            val intent = result.data
            val uri = intent?.data

            if(uri != null) {
                setWorkDirectory(uri, true)
            }
        }
    }

    fun workFiles() : List<DocumentFile>? {
        val dir = workDirectory

        if(dir == null)
            return null

        val tree = DocumentFile.fromTreeUri(this, dir)

        if(tree == null || !tree.isDirectory)
            return null

        return tree.listFiles().asList().filter {!(it.name?.startsWith(".trashed") ?: true)}
    }

    fun selectWorkFile(doc: DocumentFile?, setWorkFilePref: Boolean) {
        val saveErr = saveChangesBlocking(this)

        if(saveErr != null)
            Toast.makeText(this, "ERROR: Failed to save ($saveErr)", Toast.LENGTH_LONG).show()

        mutableProducts.clear()
        mutableProductFlags.clear()
        dataScreen.reportClear()
        selectedWorkFile = doc

        if(doc != null) {
            if(!readPLUFile(doc.uri)) {
                selectedWorkFile = null
            }
        }

        unsavedChanges = false // We just loaded the file, no need to save it

        if(selectedWorkFile == null)
            dataScreen.searchProducts()

        if(setWorkFilePref)
            getPreferences(MODE_PRIVATE).edit().putString(WORK_FILE_PREF_KEY, doc?.name).commit()
    }

    fun createEmptyWorkFile(name: String) {
        val tree = workTree(this)

        if(tree == null) {
            Toast.makeText(this, "ERROR: No work directory", Toast.LENGTH_LONG).show()
            return
        }

        val finalName = changedExtension(name.trim(), ".plu")

        if(tree.findFile(finalName) != null) {
            Toast.makeText(this, "ERROR: File already exists with that name", Toast.LENGTH_LONG).show()
            return
        }

        val newFile = tree.createFile(GENERIC_MIME, finalName)

        if(newFile == null) {
            Toast.makeText(this, "ERROR: Failed to create file", Toast.LENGTH_LONG).show()
            return
        }

        val stream = contentResolver.openOutputStream(newFile.uri, "wt")

        if(stream == null) {
            newFile.delete()
            Toast.makeText(this, "ERROR: Failed to open output stream", Toast.LENGTH_LONG).show()
            return
        }

        if(!writePLUHeader(stream)) {
            newFile.delete()
            Toast.makeText(this, "ERROR: Failed to write header", Toast.LENGTH_LONG).show()
            return
        }

        stream.close()
        selectWorkFile(newFile, true)
    }

    // If replaceProductUserContent is false, only product names are overwritten when IDs clash
    fun mergeChanges(newFlags: List<ProductFlag>?, newProducts: List<Product>?, replaceProductUserContent: Boolean) {
        // Flags
        if(newFlags != null) {
            newFlags.forEach { new ->
                val match = productFlags.indexOfFirst { old -> old.mask == new.mask }

                if (match == -1) {
                    mutableProductFlags.add(new)
                } else {
                    mutableProductFlags[match] = new
                }
            }

            mutableProductFlags.sortBy { it.mask }
        }

        dataScreen.reportFlagColorsChange()

        // Products
        if(newProducts != null) {
            newProducts.forEach { new ->
                val match = products.indexOfFirst { old -> old.id == new.id }

                if (match == -1) {
                    mutableProducts.add(new)
                } else {
                    if(replaceProductUserContent)
                        mutableProducts[match] = new
                    else {
                        mutableProducts[match] = products[match].copy(name = new.name)
                    }
                }
            }

            mutableProducts.sortBy { it.id }
        }

        unsavedChanges = true
        dataScreen.searchProducts()
    }

    fun addFlag(name: String, hue: Float = -1f) {
        if(productFlags.size == MAX_PRODUCT_FLAGS) {
            Toast.makeText(this, "ERROR: No more flags can be created", Toast.LENGTH_LONG).show()
            return
        }

        val trimmed = name.trim()

        if(trimmed.isEmpty()) {
            Toast.makeText(this, "ERROR: Flag name cannot be empty", Toast.LENGTH_LONG).show()
            return
        }

        val new = ProductFlag(trimmed, maskFromIndex(productFlags.size), hue)
        mutableProductFlags.add(new)
        dataScreen.reportFlagColorsChange()
        unsavedChanges = true
    }

    fun modifyFlag(index: Int, name: String? = null, hue: Float? = null) {
        val flag = productFlags.getOrNull(index)

        if(flag != null) {
            mutableProductFlags[index] = flag.copy(
                name = name?.trim() ?: flag.name,
                hue = hue ?: flag.hue
            )

            dataScreen.reportFlagColorsChange()
            unsavedChanges = true
        } else {
            Toast.makeText(this, ERROR_BAD_FLAG_INDEX, Toast.LENGTH_LONG).show()
            return
        }
    }

    fun deleteFlag(flagIndex: Int) {
        if(flagIndex < 0 || flagIndex >= productFlags.size) {
            Toast.makeText(this, ERROR_BAD_FLAG_INDEX, Toast.LENGTH_LONG).show()
            return
        }

        mutableProductFlags.removeAt(flagIndex)

        // Get new masks of all flags after the deleted flag
        for(i in flagIndex until productFlags.size) {
            mutableProductFlags[i] = productFlags[i].copy(mask = maskFromIndex(i))
        }

        // Update all products to remove the bit of the deleted flag
        for(i in products.indices) {
            val old = products[i]
            mutableProducts[i] = old.copy(flags = removeBits(old.flags, flagIndex, 1))
        }

        dataScreen.reportDeletedFlag(flagIndex)
        unsavedChanges = true
        dataScreen.searchProducts()
    }

    fun moveFlag(source: Int, target: Int) {
        if(source < 0 || source >= productFlags.size || target < 0 || target >= productFlags.size || source == target) {
            Toast.makeText(this, ERROR_BAD_FLAG_INDEX, Toast.LENGTH_LONG).show()
            return
        }

        mutableProductFlags.add(if(source < target) target + 1 else target, productFlags[source])
        mutableProductFlags.removeAt(if(source < target) source else source + 1)

        // Get new masks of all flags that have been moved around
        for(i in min(source, target) .. max(source, target)) {
            mutableProductFlags[i] = mutableProductFlags[i].copy(mask = maskFromIndex(i))
        }

        // Update all products
        for(i in products.indices) {
            val old = products[i]
            mutableProducts[i] = old.copy(flags = moveBit(old.flags, source, target))
        }

        dataScreen.reportMovedFlag(source, target)
        unsavedChanges = true

        /* HACK: Forces product list recomposition so its colors are corrected immediately even
        while the flag dialog is opened */
        dataScreen.searchProducts()
    }

    fun modifyProduct(product: Product, flags: Int? = null, aliases: List<ProductName>? = null) {
        val index = products.indexOf(product)

        if(index != -1) {
            mutableProducts[index] = product.copy(flags = flags ?: product.flags, aliases = aliases ?: product.aliases)
            unsavedChanges = true
            dataScreen.reportModifiedProduct(index)
        }
    }

    fun setProductAlias(productID: Int, aliasIndex: Int, aliasString: String) {
        val product = productFromID(productID)
        val trimmedString = aliasString.trim()

        if(trimmedString.isEmpty()) {
            Toast.makeText(this, "ERROR: Alias cannot be empty", Toast.LENGTH_LONG).show()
            return
        }

        if(product == null) {
            Toast.makeText(this, ERROR_BAD_PRODUCT_ID, Toast.LENGTH_LONG).show()
            return
        }

        val newAlias = ProductName(trimmedString)

        if(aliasIndex == -1) {
            // Add new alias
            modifyProduct(product, aliases = if(product.aliases != null) product.aliases.plus(newAlias) else listOf(newAlias))
        } else {
            // Change an alias
            if(product.aliases == null || aliasIndex < 0 || aliasIndex >= product.aliases.size) {
                Toast.makeText(this, ERROR_BAD_ALIAS_INDEX, Toast.LENGTH_LONG).show()
                return
            }

            modifyProduct(product, aliases = product.aliases.toMutableList().apply {this[aliasIndex] = newAlias})
        }
    }

    fun deleteProductAlias(productID: Int, aliasIndex: Int) {
        val product = productFromID(productID)

        if(product == null) {
            Toast.makeText(this, ERROR_BAD_PRODUCT_ID, Toast.LENGTH_LONG).show()
            return
        }

        if(product.aliases == null || aliasIndex < 0 || aliasIndex >= product.aliases.size) {
            Toast.makeText(this, ERROR_BAD_ALIAS_INDEX, Toast.LENGTH_LONG).show()
            return
        }

        modifyProduct(product, aliases = product.aliases.toMutableList().apply {this.removeAt(aliasIndex)})
    }

    fun setBackupPeriodMinutes(value: Int) {
        backupPeriodMinutes = max(0, value)
        getPreferences(MODE_PRIVATE).edit()
            .putInt(BACKUP_PERIOD_MINUTES_PREF_KEY, backupPeriodMinutes)
            .commit()
    }

    fun setBackupPeriodHours(value: Int) {
        backupPeriodHours = max(0, value)
        getPreferences(MODE_PRIVATE).edit()
            .putInt(BACKUP_PERIOD_HOURS_PREF_KEY, backupPeriodHours)
            .commit()
    }

    fun setMaxNumBackups(value: Int) {
        maxNumBackups = max(0, value)
        getPreferences(MODE_PRIVATE).edit()
            .putInt(MAX_NUM_BACKUPS_PREF_KEY, maxNumBackups)
            .commit()
    }

    /*
    PRIVATE
    */

    private val dataScreen = DataScreen()
    private val recorderScreen = RecorderScreen(this)

    private fun setWorkDirectory(uri: Uri?, clearWorkFilePref: Boolean) {
        if(workDirectory == uri) {
            return
        }

        selectWorkFile(null, clearWorkFilePref)

        val permissions = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        var alreadyPersisted = false

        contentResolver.persistedUriPermissions.forEach {
            if(it.uri == uri) {
                alreadyPersisted = true
            } else {
                contentResolver.releasePersistableUriPermission(it.uri, permissions)
            }
        }

        workDirectory = uri

        if(!alreadyPersisted && uri != null) {
            contentResolver.takePersistableUriPermission(uri, permissions)
        }
    }

    // True on success
    private fun readPLUFile(uri: Uri) : Boolean {
        try {
            val stream = contentResolver.openInputStream(uri)

            if(stream != null) {
                val parser = PLUParser()
                val err = parser.parseBytes(stream.readBytes())
                stream.close()

                if(err == null)
                    mergeChanges(parser.flags, parser.products, true)
                else {
                    Toast.makeText(this, "ERROR: ${err.message}", Toast.LENGTH_LONG).show()
                    return false
                }
            } else {
                Toast.makeText(this, "ERROR: Could not open file", Toast.LENGTH_LONG).show()
                return false
            }
        } catch(e: FileNotFoundException) {
            Toast.makeText(this, "ERROR: File not found", Toast.LENGTH_LONG).show()
            return false
        }

        return true
    }

    private fun saveChangesInService() {
        try {
            startService(Intent(this, SaveService::class.java))
        } catch(_: Exception) { }
        /* An exception (android.app.BackgroundServiceStartNotAllowedException) was thrown because
        the app started in the background, but we don't care about saving then. The point of the
        SaveService in the first place is to initiate the save before the activity goes into the
        background and Android cuts off its execution. */
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    @Serializable
    private object ProductListRoute

    @Serializable
    private object RecorderRoute

    @Composable
    private fun PLUkupNavHost(navController: NavHostController = rememberNavController()) {
        NavHost(
            navController = navController,
            startDestination = ProductListRoute
        ) {
            composable<ProductListRoute> {
                dataScreen.DataScreenUI(onNavigateToRecorder = {navController.navigate(route = RecorderRoute)})
            }
            composable<RecorderRoute> {
                recorderScreen.RecorderUI()
            }
        }
    }
}

fun productFromID(id: Int) : Product? {
    val index = products.binarySearchBy(id, selector = {it.id})
    return if(index >= 0) products[index] else null
}

/* HACK: Saving can be done in a service's onStartCommand callback in order to allow big thread-
blocking file saves after the activity is no longer showing. Otherwise, Android kills the process
after a short time, even during an onPause or onStop callback. Note, the service's onStartCommand
callback is not called inside the startService call, so if the activity needs to save before making
changes it should call saveChangesBlocking directly instead of starting the service.

https://developer.android.com/guide/components/activities/process-lifecycle */
class SaveService : Service() {
    override fun onBind(intent: Intent) : IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int) : Int {
        val err = saveChangesBlocking(this)

        if(err != null) {
            try {
                NotificationManagerCompat.from(this).notify(
                    1,
                    NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.note_icon)
                        .setContentTitle("Failed to save")
                        .setContentText(err)
                        .build()
                )
            } catch(_: SecurityException) {}
        }

        stopSelf()
        return START_NOT_STICKY
    }
}

private fun workTree(context: Context) : DocumentFile? {
    return DocumentFile.fromTreeUri(context, workDirectory ?: return null)
}

private fun saveChangesBlocking(context: Context) : String? {
    if(unsavedChanges) {
        return fullSaveOverwriteBlocking(context)
    } else {
        return null
    }
}

private fun fullSaveOverwriteBlocking(context: Context) : String? {
    // Write full temp file
    val dir = workTree(context) ?: return "No work directory"
    val oldFile = selectedWorkFile ?: return "No work file"
    val temp = dir.ensureFile(GENERIC_MIME, "temp") ?: return "Failed to ensure temp file"
    val tempStream = context.contentResolver.openOutputStream(temp.uri, "wt") ?: return "Failed to open temp stream"
    if(!writePLUFile(tempStream, productFlags, products)) return "Failed to write to file"

    // Swap temp and original files
    val origName = oldFile.name ?: ""
    val nameOldExt = changedExtension(origName, ".old")
    val now = ZonedDateTime.now()
    val timeString = now.format(BACKUP_TIME_FORMATTER)
    val nameOldFinal = "${nameOldExt}_$timeString"
    var tooRecent = false

    if(!oldFile.renameTo(nameOldFinal))
        return "Failed to rename old file"

    if(oldFile.name != nameOldFinal) // There must have been a name collision, don't keep this file
        tooRecent = true

    if(!temp.renameTo(origName))
    {
        oldFile.renameTo(origName) // Try to reset
        return "Failed to rename temp file"
    }

    // Select new file
    selectedWorkFile = temp

    // Manage backups
    val backupPeriod = Duration.ofMinutes(backupPeriodHours.toLong() * 60 + backupPeriodMinutes)

    val otherBackupFiles = dir.listFiles().filter {
        it.name != oldFile.name && (it.name?.startsWith(nameOldExt) ?: false) && timeFromBackupName(it.name) != null
    }.sortedBy {
        timeFromBackupName(it.name)
    }

    if(!tooRecent)
        tooRecent = otherBackupFiles.any { it.name != oldFile.name && Duration.between(timeFromBackupName(it.name), now) < backupPeriod }

    // Delete oldest backup files exceeding max count
    val deleteTarget = otherBackupFiles.size + (if(tooRecent) 0 else 1) - maxNumBackups
    var numDeleted = 0

    for(f in otherBackupFiles) {
        if(numDeleted >= deleteTarget)
            break

        if(f.name != oldFile.name) {
            f.delete()
            numDeleted++
        }
    }

    // Delete previous file if it's too recent to keep as a backup
    if(tooRecent || maxNumBackups <= 0)
        oldFile.delete()

    unsavedChanges = false
    return null
}

// Replaces the extension in path (including the '.') with ext. If path has no extension, ext is
// appended.
private fun changedExtension(path: String, ext: String) : String {
    return path.substringBeforeLast('.') + ext
}

private fun DocumentFile.ensureFile(mimeType: String, displayName: String) : DocumentFile? {
    val found = this.findFile(displayName)

    if(found != null) {
        if(found.type == mimeType)
            return found
        else
            found.delete()
    }

    return this.createFile(mimeType, displayName)
}

private fun timeFromBackupName(name: String?) : ZonedDateTime? {
    return try {
        ZonedDateTime.parse((name?.substringAfterLast(".old_")), BACKUP_TIME_FORMATTER)
    } catch(_: DateTimeParseException) {
        null
    }
}