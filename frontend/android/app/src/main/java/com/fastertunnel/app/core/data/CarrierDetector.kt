package com.fastertunnel.app.core.data

import android.content.Context
import android.telephony.TelephonyManager

data class CarrierInfo(
    val name: String,
    val countryIso: String,
    val isMovistar: Boolean,
    val isClaro: Boolean,
    val isTigo: Boolean
)

object CarrierDetector {

    fun detectCarrier(context: Context): CarrierInfo {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val operatorName = telephonyManager?.networkOperatorName?.ifEmpty {
                telephonyManager.simOperatorName
            } ?: "Desconocido"

            val countryIso = telephonyManager?.networkCountryIso?.uppercase() ?: "LATAM"

            val lower = operatorName.lowercase()
            val isMovistar = lower.contains("movistar") || lower.contains("telefonica") || lower.contains("vivo")
            val isClaro = lower.contains("claro") || lower.contains("comcel") || lower.contains("telcel")
            val isTigo = lower.contains("tigo") || lower.contains("millicom")

            CarrierInfo(
                name = if (isMovistar) "Movistar" else if (isClaro) "Claro" else if (isTigo) "Tigo" else operatorName,
                countryIso = countryIso,
                isMovistar = isMovistar,
                isClaro = isClaro,
                isTigo = isTigo
            )
        } catch (e: Exception) {
            CarrierInfo(
                name = "Movistar (Predeterminado)",
                countryIso = "LATAM",
                isMovistar = true,
                isClaro = false,
                isTigo = false
            )
        }
    }
}
