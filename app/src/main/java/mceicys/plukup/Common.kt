// Unorganized helper functions

package mceicys.plukup

fun maskFromIndex(index: Int) : Int {
    return 1 shl index
}

// Shifts bits at or more-significant than bitIndex rightward by shiftAmount, filling leftmost bits
// with zeroes. Essentially "removes" shiftAmount number of bits starting at bitIndex.
fun removeBits(value: Int, bitIndex: Int, shiftAmount: Int) : Int {
    val keepMask = maskFromIndex(bitIndex) - 1 // Bits that won't change
    return (value and keepMask) or ((value.toUInt() shr shiftAmount).toInt() and keepMask.inv())
}

fun fixedBits(lhs: Int, rhs: Int, on: Boolean) : Int {
    return if(on) lhs or rhs else lhs and rhs.inv()
}

fun moveBit(value: Int, sourceIndex: Int, targetIndex: Int) : Int {
    // Remove bit at sourceIndex
    var temp = removeBits(value, sourceIndex, 1)

    // Shift to make room at targetIndex
    val keepMask = (1 shl targetIndex) - 1
    temp = (temp and keepMask) or ((temp shl 1) and keepMask.inv())

    // Copy original bit at sourceIndex to bit at targetIndex
    temp = fixedBits(temp, 1 shl targetIndex, (value and (1 shl sourceIndex)) != 0)

    return temp
}