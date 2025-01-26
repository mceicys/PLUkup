package mceicys.plukup

import android.util.Log
import android.util.Range
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.io.OutputStream
import kotlin.math.min

private const val TAG = "Product.kt"

/*--------------------------------------------------------------------------------------------------

    PRODUCT

--------------------------------------------------------------------------------------------------*/

class Product {
    val id: Int
    val name: ProductName
    val flags: Int
    val aliases: List<ProductName>?
    val compositeName: ProductName // Name and all aliases in one set of keywords

    constructor(id: Int, name: ProductName, flags: Int, aliases: List<ProductName>? = null) {
        this.id = id
        this.name = name
        this.flags = flags
        this.aliases = if(aliases.isNullOrEmpty()) null else aliases
        compositeName = calcCompositeName()
    }

    constructor(sp: SerializableProduct) {
        this.id = sp.id
        this.name = ProductName(sp.name)
        this.flags = sp.flags
        this.aliases = if(sp.aliases.isNullOrEmpty()) null else List(sp.aliases.size, {ProductName(sp.aliases[it])})
        compositeName = calcCompositeName()
    }

    fun copy(id: Int = this.id, name: ProductName = this.name, flags: Int = this.flags,
             aliases: List<ProductName>? = this.aliases) : Product {
        return Product(id, name, flags, aliases)
    }

    private fun calcCompositeName() : ProductName {
        var bigName = name.original

        if(aliases != null) {
            for (a in aliases) {
                bigName += ' ' + a.original
            }
        }

        return ProductName(bigName)
    }
}

class ProductName(name: String) {
    val original: String = name
    val cleaned: String = cleanedName(original)
}

/* HACK: Convert Products to SerializableProducts when serializing to get around
kotlinx.serialization's restrictions on class design */
@Serializable
class SerializableProduct(
    val id: Int,
    val name: String,
    val flags: Int = 0,
    val aliases: List<String>? = null
) {
    constructor(product: Product) : this(product.id, product.name.original, product.flags,
        if(product.aliases == null) null else List(product.aliases.size, {product.aliases[it].original}))
}

// Manual parsing that can understand old format versions
// Mainly for development while PLU format is unstable
fun lenientProductFromJSON(version: Int, json: JsonElement, productIndex: Int? = null) : Product? {
    val obj = json as? JsonObject

    if(obj == null) {
        Log.e(TAG, "Product (index $productIndex) is not an object")
        return null
    }

    val id = (obj["id"] as? JsonPrimitive)?.intOrNull

    if(id == null) {
        Log.e(TAG, "Product's (index $productIndex) id must be an integer")
        return null
    }

    val name = lenientProductNameStringFromJSON(version, obj["name"])

    if(name == null) {
        Log.e(TAG, "Product's (id $id) name is invalid")
        return null
    }

    val flags = (obj["flags"] as? JsonPrimitive)?.intOrNull ?: 0

    val aliases = mutableListOf<ProductName>()
    val aliasesArr = obj["aliases"] as? JsonArray

    if(aliasesArr != null) {
        for(aliasIndex in aliasesArr.indices) {
            val aliasString = lenientProductNameStringFromJSON(version, aliasesArr[aliasIndex])

            if(aliasString == null) {
                Log.e(TAG, "Invalid alias (index $aliasIndex) in product '$name' (id $id)")
                return null
            }

            aliases.add(ProductName(aliasString))
        }
    }

    return Product(id, ProductName(name), flags, aliases)
}

fun lenientProductNameStringFromJSON(version: Int, json: JsonElement?) : String? {
    if(json is JsonPrimitive) {
        return json.contentOrNull
    } else if(json is JsonObject) {
        // This might be a serialized ProductName
        return (json["original"] as? JsonPrimitive)?.content
    } else {
        return null
    }
}

// Returns a name that's easier to search for
fun cleanedName(str: String) : String {
    return str.replace(Regex("[\n\r\t]"), " ").replace(Regex("[^a-zA-Z0-9 ]"), "")
}

/*--------------------------------------------------------------------------------------------------

    FLAG

--------------------------------------------------------------------------------------------------*/

@Serializable
class ProductFlag(val name: String, val mask: Int, val hue: Float = -1f) {
    fun copy(name: String = this.name, mask: Int = this.mask, hue: Float = this.hue) : ProductFlag {
        return ProductFlag(name, mask, hue)
    }
}

/*
fun lenientProductFlagFromJSON(version: Int, json: JsonElement, flagIndex: Int? = null) : ProductFlag? {
    val obj = json as? JsonObject

    if(obj == null) {
        Log.e(TAG, "Flag (index $flagIndex) is not an object")
        return null
    }

    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull

    if(name == null) {
        Log.e(TAG, "Flag's (index $flagIndex) name is invalid")
        return null
    }

    val mask = (obj["mask"] as? JsonPrimitive)?.intOrNull

    if(mask == null) {
        Log.e(TAG, "Flag's (index $flagIndex) mask must be an integer")
        return null
    }

    val hue = (obj["hue"] as? JsonPrimitive)?.floatOrNull ?: -1f

    return ProductFlag(name, mask, hue)
}
*/

/*--------------------------------------------------------------------------------------------------

    SERIAL DATA

--------------------------------------------------------------------------------------------------*/

// Retrieves product info from serial output
fun parseSerialProducts(bytes: ByteArray) : List<Product> {
    val products = mutableListOf<Product>()
    val p = SerialParser(bytes, products)
    while(p.parseMessage() != SerialParseResult.DONE);
    return products
}

fun createTestSerialData(products: List<Product>) : ByteArray {
    val temp = ResizableByteArray(128)

    for(p in products) {
        temp.add(2)
        temp.add(2)
        val serialProd = stringToBinary("32g#@31p#${p.id}@31dt${p.name.original}@3")
        temp.add(serialProd)
        temp.add(calcChecksum(serialProd).toByte())
        temp.add(3)
    }

    return temp.toByteArray()
}

/*--------------------------------------------------------------------------------------------------

    PLU DATA

--------------------------------------------------------------------------------------------------*/

/*
* PLU file format
* UTF-8 text file
* The file is a sequential list of "actions"
* Actions are surrounded by <STX> <ETX> control chars
    * Actions cannot contain <STX> and <ETX> chars
* The first action is the file signature: )>PLUKUP
* The second action is the format version: 0
* Subsequent actions are JSON objects prefixed with two chars to indicate the type of action
    * Example: <STX>fl{"name":"Obsolete","mask":1}<ETX>
    * Types:
        * fl = (re)define a ProductFlag
        * pr = (re)define a Product
* Products/flags with clashing IDs/masks should be overwritten by the latest action
*/

// Returns true on success
fun writePLUFile(stream: OutputStream, flags: List<ProductFlag>, products: List<Product>) : Boolean {
    if(!writePLUHeader(stream)) return false

    // Flags
    for (f in flags) {
        if(!writePLUFlag(stream, f)) return false
    }

    // Products
    for (p in products) {
        if(!writePLUProduct(stream, p)) return false
    }

    return true
}

fun writePLUHeader(stream: OutputStream) : Boolean {
    try {
        // Signature
        stream.write(STX)
        stream.write(")>PLUKUP".toByteArray())
        stream.write(ETX)

        // Version
        stream.write(STX)
        stream.write("0".toByteArray())
        stream.write(ETX)
    } catch(_: IOException) {
        return false
    }

    return true
}

fun writePLUFlag(stream: OutputStream, flag: ProductFlag) : Boolean {
    try {
        stream.write(STX)
        stream.write("fl".toByteArray())
        Json.encodeToStream(flag, stream)
        stream.write(ETX)
    } catch(_: IOException) {
        return false
    }

    return true
}

fun writePLUProduct(stream: OutputStream, product: Product) : Boolean {
    try {
        stream.write(STX)
        stream.write("pr".toByteArray())
        Json.encodeToStream(SerializableProduct(product), stream)
        stream.write(ETX)
    } catch(_: IOException) {
        return false
    }

    return true
}

class PLUParser() {
    val flags = mutableListOf<ProductFlag>()
    val products = mutableListOf<Product>()

    fun clear() {
        flags.clear()
        products.clear()
    }

    // bytes should be the contents of a whole PLU file
    // Appends parsed objects to member lists
    // Returns null if bytes was parsed successfully
    // Class state can be modified even on failure
    fun parseBytes(bytes: ByteArray) : ParseError? {
        val signature = findAction(bytes, 0)
        var actionIndex = 0

        if(signature.text != ")>PLUKUP") {
            return ParseError("Bad file signature", actionIndex, signature.text)
        }

        val version = findAction(bytes, signature.endIndex)
        val versionInt = version.text?.toIntOrNull()
        actionIndex++

        if(versionInt != 0) {
            return ParseError("Unknown file version", actionIndex, version.text)
        }

        var end = version.endIndex

        while(true) {
            val action = findAction(bytes, end, true)
            actionIndex++
            end = action.endIndex

            if(action.text == null)
                break

            try {
                when (action.type) {
                    "fl" -> {
                        flags.add(Json.decodeFromString<ProductFlag>(action.text))
                    }
                    "pr" -> {
                        val prod = lenientProductFromJSON(versionInt, Json.decodeFromString<JsonElement>(action.text), actionIndex)

                        if(prod != null) {
                            products.add(prod)
                        } else {
                            return ParseError("Invalid product definition", actionIndex, action.text)
                        }
                    }
                    else -> {
                        return ParseError("Unknown action type", actionIndex, action.text)
                    }
                }
            } catch(e: SerializationException) {
                return ParseError("Serialization exception", actionIndex, action.text)
            } catch(e: IllegalArgumentException) {
                return ParseError("Illegal argument exception", actionIndex, action.text)
            }
        }

        return null
    }

    class ParseError(val message: String, val actionIndex: Int, val actionText: String?)

    private class Action(val type: String?, val text: String?, val endIndex: Int)

    // Action.text is null if end of file was reached
    // Pass Action.endIndex to index to keep getting the next action
    // Separates first two bytes of action into Action.type if getType is true
    private fun findAction(bytes: ByteArray, index: Int, getType: Boolean = false) : Action {
        val range = findActionRange(bytes, index)

        if(range.lower >= bytes.size) {
            return Action(null, null, range.upper)
        } else if(getType) {
            return Action(
                bytes.decodeToString(range.lower, min(range.lower + 2, range.upper)),
                bytes.decodeToString(min(range.lower + 2, range.upper), range.upper),
                range.upper)
        } else {
            return Action(null, bytes.decodeToString(range.lower, range.upper), range.upper)
        }
    }

    // Finds the next STX-ETX range at or after index
    // Returns range where lower is the first char of the action and upper is the ETX or end-of-file
    // upper - lower is the length of the text
    // If lower >= bytes.size, no text was found
    private fun findActionRange(bytes: ByteArray, index: Int) : Range<Int> {
        // Find the STX char
        var start = index

        while(start < bytes.size && bytes[start].compareTo(STX) != 0) {
            start++
        }

        if(start >= bytes.size) {
            return Range(start, start)
        }

        start++ // Go past STX
        var end = start

        while(end < bytes.size && bytes[end].compareTo(ETX) != 0) {
            end++
        }

        return Range(start, end)
    }
}

/*--------------------------------------------------------------------------------------------------

    PRIVATE

--------------------------------------------------------------------------------------------------*/

private enum class SerialParseResult {
    GOOD, UNFIT, DONE, ERROR
}

private const val STX   = 2
private const val ETX   = 3
private const val ETB   = 23
private const val US    = 31

// If a parse function returns GOOD or DONE, it should set index to the next byte to be parsed
// Otherwise, the function must not modify index
private class SerialParser(private val bytes: ByteArray, private val products: MutableList<Product>, private var index: Int = 0) {
    fun parseMessage() : SerialParseResult {
        val save = index

        if(index >= bytes.size)
            return SerialParseResult.DONE

        if(isEndByte(bytes[index])) {
            index++
            return SerialParseResult.GOOD
        } else if(isStartByte(bytes[index])) {
            index++
            val res = parseProduct()

            if(res == SerialParseResult.ERROR) {
                index = save
                return res
            }

            // Skip rest of message until same-level end-byte
            var lvl = 1
            var checksumExpected = res == SerialParseResult.GOOD

            while(index < bytes.size) {
                if(checksumExpected)
                    checksumExpected = false
                else { // Only react to byte if it's not supposed to be a checksum
                    if (isStartByte(bytes[index]))
                        lvl++
                    else if (isEndByte(bytes[index])) {
                        lvl--

                        if(lvl == 1)
                            checksumExpected = true
                    }
                }

                index++

                if(lvl == 0)
                    break
            }
        }

        return SerialParseResult.GOOD
    }

    private fun parseProduct() : SerialParseResult {
        var i = index

        // Check for product message signature
        if(i + 3 > bytes.size || !isStartByte(bytes[i]) || bytes[i + 1].compareTo('3'.code) != 0 || bytes[i + 2].compareTo('2'.code) != 0)
            return SerialParseResult.UNFIT

        i += 3

        // Find relevant product details
        var id: Int? = null
        var name: String? = null

        while(i < bytes.size) {
            val unitStart = i
            var unitEnd = i

            // Find ETX or unit separator
            while(unitEnd < bytes.size && !isEndByte(bytes[unitEnd]) && bytes[unitEnd].compareTo(US) != 0)
                unitEnd++

            val unitLength = unitEnd - unitStart

            if(unitLength > 2) {
                // Check for relevant data
                val sig = bytes.decodeToString(i, i + 2)

                if (sig == "p#") {
                    id = bytes.decodeToString(unitStart + sig.length, unitEnd).toInt()
                } else if (sig == "dt") {
                    name = bytes.decodeToString(unitStart + sig.length, unitEnd)
                }
            }

            if(unitEnd >= bytes.size) { // End of file
                i = unitEnd
                break
            }

            i = unitEnd + 1

            if(isEndByte(bytes[unitEnd]))
                break // End of message
        }

        if(id == null || name == null)
            return SerialParseResult.UNFIT

        products.add(Product(id, ProductName(name), 0))
        index = i
        return SerialParseResult.GOOD
    }

    private fun isStartByte(b: Byte) : Boolean {
        return b.compareTo(STX) == 0
    }

    private fun isEndByte(b: Byte) : Boolean {
        return b.compareTo(ETX) == 0 || b.compareTo(ETB) == 0
    }
}