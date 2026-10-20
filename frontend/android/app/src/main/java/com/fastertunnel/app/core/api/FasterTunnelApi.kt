package com.fastertunnel.app.core.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface FasterTunnelApi {

    @GET("/api/v1/health")
    suspend fun getHealth(): Response<ApiResponse<HealthData>>

    @POST("/api/v1/auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<ApiResponse<LoginData>>

    @POST("/api/v1/auth/logout")
    suspend fun logout(
        @Header("Authorization") token: String
    ): Response<ApiResponse<Unit>>

    @POST("/api/v1/auth/heartbeat")
    suspend fun heartbeat(
        @Header("Authorization") token: String,
        @Body request: HeartbeatRequest
    ): Response<ApiResponse<HeartbeatData>>

    @GET("/api/v1/servers")
    suspend fun getServers(
        @Header("Authorization") token: String
    ): Response<ApiResponse<ServersData>>

    @GET("/api/v1/servers/ping")
    suspend fun getServersPing(
        @Header("Authorization") token: String
    ): Response<ApiResponse<Map<String, Any>>>

    @GET("/api/v1/tweaks")
    suspend fun getTweaks(
        @Header("Authorization") token: String,
        @Query("country") country: String? = null,
        @Query("carrier") carrier: String? = null
    ): Response<ApiResponse<TweaksData>>
}
