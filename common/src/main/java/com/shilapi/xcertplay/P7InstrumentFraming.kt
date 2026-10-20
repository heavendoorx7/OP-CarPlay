package com.shilapi.xcertplay

/**
 * AMap calibration: the captured 664-square secondary frame places the vehicle and
 * current-road label at x ~= 0.75. On the 1328x664 canvas the phone relayout moves
 * that anchor to x ~= 876 (measured from a live frame). Take a 664-square centered
 * there, keeping vertical field and pixel density unchanged.
 * This is not an assumption about other navigation applications.
 */
internal object P7InstrumentFraming {
    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int) {
        fun writeUvMatrix(sourceWidth: Int, sourceHeight: Int, out: FloatArray) {
            require(sourceWidth > 0 && sourceHeight > 0 && out.size >= 16)
            out.fill(0f)
            out[0] = width.toFloat() / sourceWidth
            out[5] = height.toFloat() / sourceHeight
            out[10] = 1f
            out[12] = left.toFloat() / sourceWidth
            out[13] = top.toFloat() / sourceHeight
            out[15] = 1f
        }
    }

    fun crop(sourceWidth: Int, sourceHeight: Int): Crop {
        require(sourceWidth > 0 && sourceHeight > 0)
        // Never crop a square fallback or an unexpected decoder geometry.
        return if (sourceWidth == 1328 && sourceHeight == 664) {
            Crop(544, 0, 664, 664)
        } else {
            Crop(0, 0, sourceWidth, sourceHeight)
        }
    }
}
