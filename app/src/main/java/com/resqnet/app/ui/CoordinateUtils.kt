package com.resqnet.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.resqnet.app.R
import java.util.Locale

object CoordinateUtils {

    /**
     * Regex matching geographic coordinates in decimal degrees:
     * Examples: 19.0760, 72.8777 or 19.07600, 72.87770 or -33.8688, 151.2093
     */
    val COORDINATE_REGEX = Regex(
        """(?<![0-9a-zA-Z])([-+]?(?:[1-8]?\d\.\d+|90(?:\.0+)?)),\s*([-+]?(?:180(?:\.0+)?|(?:1[0-7]\d|\d{1,2})\.\d+))(?![0-9a-zA-Z]|\.\d)"""
    )

    data class ExtractedCoordinate(
        val latitude: Double,
        val longitude: Double,
        val start: Int,
        val end: Int,
        val text: String
    )

    fun findCoordinates(text: String): List<ExtractedCoordinate> {
        return COORDINATE_REGEX.findAll(text).mapNotNull { match ->
            val lat = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                ExtractedCoordinate(
                    latitude = lat,
                    longitude = lon,
                    start = match.range.first,
                    end = match.range.last + 1,
                    text = match.value
                )
            } else null
        }.toList()
    }

    /**
     * Finds coordinates in [rawText] and makes them clickable links taking user to [NavigateActivity].
     */
    fun highlightCoordinates(
        textView: TextView,
        rawText: String,
        isOutgoing: Boolean,
        onCoordinateClicked: ((lat: Double, lon: Double) -> Unit)? = null
    ) {
        val coordinates = findCoordinates(rawText)
        if (coordinates.isEmpty()) {
            textView.movementMethod = null
            textView.text = rawText
            return
        }

        val spannable = SpannableStringBuilder(rawText)
        val linkColor = if (isOutgoing) {
            Color.parseColor("#F2B544") // High contrast warm amber on dark navy
        } else {
            Color.parseColor("#B37400") // Deep amber on white bubble for AA contrast
        }

        for (coord in coordinates) {
            val span = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    if (onCoordinateClicked != null) {
                        onCoordinateClicked(coord.latitude, coord.longitude)
                    } else {
                        openNavigate(widget.context, coord.latitude, coord.longitude, "Pin: ${coord.text}")
                    }
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = linkColor
                    ds.isUnderlineText = true
                    ds.isFakeBoldText = true
                }
            }
            spannable.setSpan(span, coord.start, coord.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        textView.text = spannable
        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.highlightColor = Color.TRANSPARENT
    }

    /**
     * Launches or brings [NavigateActivity] to the front, focused on the given coordinates.
     */
    fun openNavigate(context: Context, lat: Double, lon: Double, label: String = "Shared Location") {
        val intent = Intent(context, NavigateActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(NavigateActivity.EXTRA_TARGET_LAT, lat)
            putExtra(NavigateActivity.EXTRA_TARGET_LON, lon)
            putExtra(NavigateActivity.EXTRA_TARGET_LABEL, label)
        }
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        if (context is android.app.Activity) {
            context.overridePendingTransition(0, 0)
        }
    }

    /**
     * Inserts formatted coordinates into an [EditText] at cursor position without sending.
     */
    fun insertCoordinatesIntoInput(editText: EditText, lat: Double, lon: Double) {
        val formatted = "%.5f, %.5f".format(Locale.US, lat, lon)
        val currentText = editText.text.toString()
        val cursor = editText.selectionStart.coerceAtLeast(0)

        val newText = if (currentText.isEmpty()) {
            formatted
        } else if (cursor > 0 && currentText.getOrNull(cursor - 1) != ' ') {
            currentText.substring(0, cursor) + " $formatted" + currentText.substring(cursor)
        } else {
            currentText.substring(0, cursor) + formatted + currentText.substring(cursor)
        }

        editText.setText(newText)
        val insertedLen = formatted.length + if (currentText.isNotEmpty() && cursor > 0 && currentText.getOrNull(cursor - 1) != ' ') 1 else 0
        val newCursor = (cursor + insertedLen).coerceAtMost(newText.length)
        editText.setSelection(newCursor)
        editText.requestFocus()
    }

    /**
     * Obtains the phone's best available coordinates via LocationManager (GPS or Network).
     * First checks last known location (instant), falls back to a one-shot request.
     */
    @Suppress("MissingPermission")
    fun fetchCoordinates(
        context: Context,
        onSuccess: (latitude: Double, longitude: Double) -> Unit,
        onError: (message: String) -> Unit
    ) {
        if (!PermissionHelper.hasPermissions(context)) {
            onError("Location permission required")
            return
        }

        val locationManager = context.getSystemService(LocationManager::class.java)
        if (locationManager == null) {
            onError("Location service not available")
            return
        }

        if (!locationManager.isLocationEnabled) {
            onError("Please enable GPS/Location in device settings")
            return
        }

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        // Try last known fix
        val lastFix = providers.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.minByOrNull { it.accuracy }

        if (lastFix != null) {
            onSuccess(lastFix.latitude, lastFix.longitude)
            return
        }

        // Request single update from first enabled provider
        val activeProvider = providers.firstOrNull { locationManager.isProviderEnabled(it) }
        if (activeProvider != null) {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    onSuccess(location.latitude, location.longitude)
                }

                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            runCatching {
                locationManager.requestLocationUpdates(
                    activeProvider,
                    1000L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            }.onFailure {
                onError("Unable to acquire location fix")
            }
        } else {
            onError("No active location provider found")
        }
    }
}
