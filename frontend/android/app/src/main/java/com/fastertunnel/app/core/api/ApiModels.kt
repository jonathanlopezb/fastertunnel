package com.fastertunnel.app.core.api

import com.google.gson.annotations.SerializedName

// Respuesta base de la API de Go
data class ApiResponse<T>(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("data") val data: T?,
    @SerializedName("error") val error: String?
)

// DTO de Salud
data class HealthData(
    @SerializedName("status") val status: String,
    @SerializedName("version") val version: String,
    @SerializedName("service") val service: String
)

// Heartbeat Request / Response
data class HeartbeatRequest(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("gateway_id") val gatewayId: String? = null
)

data class HeartbeatData(
    @SerializedName("status") val status: String,
    @SerializedName("valid") val valid: Boolean
)

data class CreateWireGuardProfileRequest(
    @SerializedName("public_key") val publicKey: String
)

data class WireGuardProfileData(
    @SerializedName("node_id") val nodeId: String,
    @SerializedName("name") val name: String,
    @SerializedName("address") val address: String,
    @SerializedName("dns") val dns: String,
    @SerializedName("endpoint") val endpoint: String,
    @SerializedName("server_public_key") val serverPublicKey: String,
    @SerializedName("allowed_ips") val allowedIPs: List<String>,
    @SerializedName("persistent_keepalive") val persistentKeepalive: Int
)

// Servidores Gateway
data class ServersData(
    @SerializedName("servers") val servers: List<ServerNodeDto>,
    @SerializedName("signature") val signature: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("version") val version: Int
)

data class ServerNodeDto(
    @SerializedName("ID") val id: Long,
    @SerializedName("node_id") val nodeId: String,
    @SerializedName("name") val name: String,
    @SerializedName("country_code") val countryCode: String,
    @SerializedName("flag_emoji") val flagEmoji: String,
    @SerializedName("host") val host: String,
    @SerializedName("port") val port: Int,
    @SerializedName("transport") val transport: String,
    @SerializedName("sni_host") val sniHost: String?,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("is_premium") val isPremium: Boolean,
    @SerializedName("ping_ms") val pingMs: Int,
    @SerializedName("max_users") val maxUsers: Int,
    @SerializedName("current_users") val currentUsers: Int
)

// Tweaks / Payloads
data class TweaksData(
    @SerializedName("tweaks") val tweaks: List<TweakDto>,
    @SerializedName("signature") val signature: String,
    @SerializedName("timestamp") val timestamp: Long
)

data class TweakDto(
    @SerializedName("ID") val id: Long,
    @SerializedName("tweak_id") val tweakId: String,
    @SerializedName("label") val label: String,
    @SerializedName("carrier") val carrier: String,
    @SerializedName("country") val country: String,
    @SerializedName("sni_spoof") val sniSpoof: String?,
    @SerializedName("payload_template") val payloadTemplate: String?,
    @SerializedName("transport") val transport: String,
    @SerializedName("recommended_gateway") val recommendedGateway: String?,
    @SerializedName("is_active") val isActive: Boolean
)
