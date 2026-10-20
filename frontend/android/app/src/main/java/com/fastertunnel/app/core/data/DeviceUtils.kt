package com.fastertunnel.app.core.data

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

object DeviceUtils {

    fun getDeviceHardwareId(context: Context): String {
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: UUID.randomUUID().toString()

            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(androidId.toByteArray())
            digest.fold("") { str, it -> str + "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            UUID.randomUUID().toString().take(16)
        }
    }
}
