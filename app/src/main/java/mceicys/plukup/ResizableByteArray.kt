package mceicys.plukup

class ResizableByteArray(initSize: Int, initSet: (Int) -> Byte = {0}) {
    operator fun get(i: Int) : Byte {
        return bytes[i]
    }

    operator fun set(i: Int, b: Byte) {
        ensure(i + 1)
        bytes[i] = b
        length = Integer.max(length, i + 1)
    }

    fun ensure(need: Int) {
        if(size < need) {
            var newSize = Integer.max(1, size)

            while (newSize < need)
                newSize *= 2

            bytes = bytes.copyOf(newSize)
        }
    }

    fun add(b: Byte) {
        ensure(length + 1)
        this[length] = b
    }

    fun clear() {
        length = 0
    }

    fun toByteArray() : ByteArray {
        return bytes.copyOf(length)
    }

    var length = 0
        private set

    val size get() = bytes.size
    private var bytes = ByteArray(initSize, initSet)
}