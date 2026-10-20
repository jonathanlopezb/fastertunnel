package com.fastertunnel.app.core.data

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
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
            // En teléfonos dual-SIM, leer TODAS las SIMs activas para detectar el operador correcto.
            // La API TelephonyManager.networkOperatorName sólo devuelve la SIM en slot 0 (o la de datos),
            // ignorando la SIM 1 (slot 2). Esto causaba que Claro (slot 0) se detectara como
            // principal incluso cuando el usuario quería usar Movistar (slot 1).
            val allOperatorNames = mutableListOf<String>()

            // Método 1: SubscriptionManager (API 22+) — lee todos los slots SIM
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                            as? SubscriptionManager
                    val subs = subManager?.activeSubscriptionInfoList
                    subs?.forEach { info ->
                        val name = info.carrierName?.toString()?.lowercase() ?: ""
                        if (name.isNotBlank()) allOperatorNames.add(name)
                    }
                } catch (_: Exception) {}
            }

            // Método 2: TelephonyManager estándar como fallback
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val networkName = telephonyManager?.networkOperatorName?.lowercase() ?: ""
            val simName = telephonyManager?.simOperatorName?.lowercase() ?: ""
            if (networkName.isNotBlank()) allOperatorNames.add(networkName)
            if (simName.isNotBlank()) allOperatorNames.add(simName)

            val countryIso = telephonyManager?.networkCountryIso?.uppercase() ?: "CO"

            // Detectar si CUALQUIERA de las SIMs es Movistar/Claro/Tigo
            val isMovistar = allOperatorNames.any { it.contains("movistar") || it.contains("telefonica") || it.contains("vivo") }
            val isClaro    = allOperatorNames.any { it.contains("claro") || it.contains("comcel") || it.contains("telcel") }
            val isTigo     = allOperatorNames.any { it.contains("tigo") || it.contains("millicom") }

            // Elegir nombre representativo (prioridad: Movistar > Claro > Tigo > primer nombre)
            val displayName = when {
                isMovistar -> "Movistar"
                isClaro    -> "Claro"
                isTigo     -> "Tigo"
                else       -> allOperatorNames.firstOrNull() ?: "Desconocido"
            }

            // Log de debug para diagnóstico en los logs del túnel
            val allNames = allOperatorNames.joinToString(", ")

            CarrierInfo(
                name = displayName,
                countryIso = countryIso,
                isMovistar = isMovistar,
                isClaro = isClaro,
                isTigo = isTigo
            )
        } catch (e: Exception) {
            CarrierInfo(
                name = "Movistar (Predeterminado)",
                countryIso = "CO",
                isMovistar = true,
                isClaro = false,
                isTigo = false
            )
        }
    }
}
