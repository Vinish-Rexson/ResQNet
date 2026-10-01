package com.resqnet.app.navigation

/** Decodes Valhalla's six-decimal encoded route shapes. */
object Polyline6Decoder {
    fun decode(encoded: String): List<GeoPoint> {
        if (encoded.isEmpty()) return emptyList()
        val result = ArrayList<GeoPoint>()
        var index = 0
        var latitude = 0
        var longitude = 0
        while (index < encoded.length) {
            val lat = decodeValue(encoded, index) ?: return result
            index = lat.nextIndex
            latitude += lat.value
            val lon = decodeValue(encoded, index) ?: return result
            index = lon.nextIndex
            longitude += lon.value
            result += GeoPoint(latitude / 1e6, longitude / 1e6)
        }
        return result
    }

    private fun decodeValue(value: String, startIndex: Int): DecodedValue? {
        var index = startIndex
        var shift = 0
        var result = 0
        while (index < value.length) {
            val chunk = value[index++].code - 63
            result = result or ((chunk and 0x1f) shl shift)
            shift += 5
            if (chunk < 0x20) {
                val decoded = if ((result and 1) == 0) result shr 1 else (result shr 1).inv()
                return DecodedValue(decoded, index)
            }
        }
        return null
    }

    private data class DecodedValue(val value: Int, val nextIndex: Int)
}
