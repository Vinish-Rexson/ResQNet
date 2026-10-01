package com.resqnet.app.navigation.spike

/**
 * Utility to decode polyline strings (Polyline5 and Polyline6)
 * used by Valhalla, OSRM, and Google Maps.
 */
object PolylineDecoder {

    /**
     * Decodes an encoded polyline string into a list of [LatLon].
     * @param encoded The polyline string.
     * @param precision Precision factor: 1e5 for standard 5-decimal polyline, 1e6 for Valhalla polyline6.
     */
    fun decode(encoded: String, precision: Double = 1e6): List<LatLon> {
        val poly = ArrayList<LatLon>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                if (index >= len) break
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                if (index >= len) break
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(LatLon(lat / precision, lng / precision))
        }
        return poly
    }
}
