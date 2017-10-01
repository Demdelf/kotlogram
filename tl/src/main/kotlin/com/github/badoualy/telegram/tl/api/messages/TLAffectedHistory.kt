package com.github.badoualy.telegram.tl.api.messages

import com.github.badoualy.telegram.tl.TLObjectUtils.SIZE_CONSTRUCTOR_ID
import com.github.badoualy.telegram.tl.TLObjectUtils.SIZE_INT32
import com.github.badoualy.telegram.tl.core.TLObject
import com.github.badoualy.telegram.tl.serialization.TLDeserializer
import com.github.badoualy.telegram.tl.serialization.TLSerializer
import java.io.IOException

/**
 * messages.affectedHistory#b45c69d1
 *
 * @author Yannick Badoual yann.badoual@gmail.com
 * @see <a href="http://github.com/badoualy/kotlogram">http://github.com/badoualy/kotlogram</a>
 */
class TLAffectedHistory() : TLObject() {
    var pts: Int = 0

    var ptsCount: Int = 0

    var offset: Int = 0

    private val _constructor: String = "messages.affectedHistory#b45c69d1"

    override val constructorId: Int = CONSTRUCTOR_ID

    constructor(
            pts: Int,
            ptsCount: Int,
            offset: Int
    ) : this() {
        this.pts = pts
        this.ptsCount = ptsCount
        this.offset = offset
    }

    @Throws(IOException::class)
    override fun serializeBody(tlSerializer: TLSerializer) = with (tlSerializer)  {
        writeInt(pts)
        writeInt(ptsCount)
        writeInt(offset)
    }

    @Throws(IOException::class)
    override fun deserializeBody(tlDeserializer: TLDeserializer) = with (tlDeserializer)  {
        pts = readInt()
        ptsCount = readInt()
        offset = readInt()
    }

    override fun computeSerializedSize(): Int {
        var size = SIZE_CONSTRUCTOR_ID
        size += SIZE_INT32
        size += SIZE_INT32
        size += SIZE_INT32
        return size
    }

    override fun toString() = _constructor

    override fun equals(other: Any?): Boolean {
        if (other !is TLAffectedHistory) return false
        if (other === this) return true

        return pts == other.pts
                && ptsCount == other.ptsCount
                && offset == other.offset
    }
    companion object  {
        const val CONSTRUCTOR_ID: Int = 0xb45c69d1.toInt()
    }
}