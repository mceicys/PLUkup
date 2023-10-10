package mceicys.plukup

class Product(val id: Int, val name: String, val cleaned: String)

fun parseProducts(bytes: ByteArray) : List<Product> {
    val products = mutableListOf<Product>()
    val p = ParseState(bytes, products)
    while(p.parseMessage() != ParseResult.DONE);
    return products
}

// Returns a name that's easier to search for
fun cleanedName(str: String) : String {
    return str.replace(Regex("[\n\r\t]"), " ").replace(Regex("[^a-zA-Z0-9 ]"), "")
}

/*
    PRIVATE
*/

private enum class ParseResult {
    GOOD, UNFIT, DONE, ERROR
}

private const val STX   = 2
private const val ETX   = 3
private const val ETB   = 23
private const val US    = 31

// If a parse function returns GOOD or DONE, it should set index to the next byte to be parsed
// Otherwise, the function must not modify index
private class ParseState(private val bytes: ByteArray, private val products: MutableList<Product>, private var index: Int = 0) {
    /* FIXME: pay attention to when a checksum should appear and ignore it since it can equal
        2 (STX), 3 (ETX), and 23 (ETB); this doesn't break parsing in practice because every message
        is followed by an extra ETX, but it's the right thing to do */
    fun parseMessage() : ParseResult {
        val save = index

        if(index >= bytes.size)
            return ParseResult.DONE

        if(isEndByte(bytes[index])) {
            index++
            return ParseResult.GOOD
        } else if(isStartByte(bytes[index])) {
            index++
            val res = parseProduct()

            if(res == ParseResult.ERROR) {
                index = save
                return res
            }

            // Skip rest of message until same-level ETX
            var lvl = 1

            while(index < bytes.size) {
                if(isStartByte(bytes[index]))
                    lvl++
                else if(isEndByte(bytes[index]))
                    lvl--

                index++

                if(lvl == 0)
                    break
            }
        }

        return ParseResult.GOOD
    }

    private fun parseProduct() : ParseResult {
        var i = index

        // Check for product message signature
        if(i + 3 > bytes.size || !isStartByte(bytes[i]) || bytes[i + 1].compareTo('3'.code) != 0 || bytes[i + 2].compareTo('2'.code) != 0)
            return ParseResult.UNFIT

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
            return ParseResult.UNFIT

        products.add(Product(id, name, cleanedName(name)))
        index = i
        return ParseResult.GOOD
    }

    private fun isStartByte(b: Byte) : Boolean {
        return b.compareTo(STX) == 0
    }

    private fun isEndByte(b: Byte) : Boolean {
        return b.compareTo(ETX) == 0 || b.compareTo(ETB) == 0
    }
}