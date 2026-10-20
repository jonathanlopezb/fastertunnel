package com.fastertunnel.app.ui.screens

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastertunnel.app.R
import com.fastertunnel.app.core.data.CarrierInfo
import com.fastertunnel.app.core.vpn.TunnelStatus
import com.fastertunnel.app.ui.theme.AmberAccent
import com.fastertunnel.app.ui.theme.BorderGlow
import com.fastertunnel.app.ui.theme.CyanPrimary
import com.fastertunnel.app.ui.theme.CyanVariant
import com.fastertunnel.app.ui.theme.DarkBackground
import com.fastertunnel.app.ui.theme.DarkSurface
import com.fastertunnel.app.ui.theme.DarkSurfaceElevated
import com.fastertunnel.app.ui.theme.DarkSurfaceVariant
import com.fastertunnel.app.ui.theme.StatusGreen
import com.fastertunnel.app.ui.theme.StatusOrange
import com.fastertunnel.app.ui.theme.StatusRed
import com.fastertunnel.app.ui.theme.TextDark
import com.fastertunnel.app.ui.theme.TextMuted
import com.fastertunnel.app.ui.theme.TextWhite
import com.fastertunnel.app.ui.viewmodel.MainViewModel

@Composable
fun MainTunnelScreen(
    viewModel: MainViewModel,
    onRequestVpnPermission: () -> Unit
) {
    val context = LocalContext.current
    val status by viewModel.tunnelStatus.collectAsState()
    val stats by viewModel.trafficStats.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val isOnline by viewModel.isBackendOnline.collectAsState()
    val latency by viewModel.backendLatency.collectAsState()

    val servers by viewModel.servers.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()

    val tweaks by viewModel.tweaks.collectAsState()
    val selectedTweak by viewModel.selectedTweak.collectAsState()

    val carrier by viewModel.carrierInfo.collectAsState()
    val customSni by viewModel.customSni.collectAsState()

    val showLogin by viewModel.showLoginDialog.collectAsState()
    val isLoggingIn by viewModel.isLoggingIn.collectAsState()
    val loginError by viewModel.loginError.collectAsState()
    val isDeviceLimitReached by viewModel.isDeviceLimitReached.collectAsState()

    var showServerSheet by remember { mutableStateOf(false) }
    var showTweakSheet by remember { mutableStateOf(false) }
    var showCustomSniDialog by remember { mutableStateOf(false) }

    val logListState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logListState.animateScrollToItem(logs.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top Bar con Logo y Estado de Render Backend ───────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = "fasterTunnel Logo",
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "fasterTunnel",
                            color = TextWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Next-Gen Injector",
                            color = CyanPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Badge de Render Backend con indicador verde/rojo
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(DarkSurfaceVariant)
                        .clickable { viewModel.checkBackendHealth() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isOnline) StatusGreen else StatusRed)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isOnline) "${latency ?: 0} ms" else "Render Offline",
                        color = if (isOnline) StatusGreen else StatusRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refrescar",
                        tint = TextMuted,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            // ── Tarjeta de Usuario / Sesión Activa ─────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurface)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (viewModel.prefs.isLoggedIn) CyanPrimary else AmberAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (viewModel.prefs.isLoggedIn)
                            "Usuario: ${viewModel.prefs.username}"
                        else
                            "Sesión no iniciada",
                        color = TextWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                IconButton(
                    onClick = {
                        if (viewModel.prefs.isLoggedIn) {
                            viewModel.logout()
                        } else {
                            viewModel.openLoginDialog()
                        }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ExitToApp,
                        contentDescription = "Cambiar cuenta",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // ── Badge de Operador SIM y Detección Movistar ─────────────────────
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SignalCellularAlt,
                        contentDescription = null,
                        tint = if (carrier.isMovistar) CyanPrimary else AmberAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SIM: ${carrier.name} (${carrier.countryIso})",
                        color = TextWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (carrier.isMovistar)
                                    CyanPrimary.copy(alpha = 0.2f)
                                else
                                    AmberAccent.copy(alpha = 0.2f)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (carrier.isMovistar) "Auto Movistar" else "Inyección Activa",
                            color = if (carrier.isMovistar) CyanPrimary else AmberAccent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ── Alerta si fue Desalojado por Otro Dispositivo (Preemption) ─────
            if (status == TunnelStatus.PREEMPTED) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = StatusRed.copy(alpha = 0.2f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, StatusRed, RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = StatusRed,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "SESIÓN INTERRUMPIDA: Tu cuenta fue iniciada en otro dispositivo. Se desconectó este túnel.",
                            color = TextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Selector de Servidor Gateway ──────────────────────────────────
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showServerSheet = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedServer?.flagEmoji ?: "🌐",
                        fontSize = 24.sp
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SERVIDOR GATEWAY",
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = selectedServer?.name ?: "Seleccionar Servidor",
                            color = TextWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = CyanPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Selector de Tweak / Payload ───────────────────────────────────
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showTweakSheet = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = AmberAccent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TWEAK / PAYLOAD",
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = selectedTweak?.label ?: "Movistar WhatsApp",
                            color = TextWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "SNI: ${selectedTweak?.sniSpoof ?: customSni}",
                            color = CyanPrimary,
                            fontSize = 11.sp
                        )
                    }
                    IconButton(
                        onClick = { showCustomSniDialog = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Editar SNI",
                            tint = CyanPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = AmberAccent
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Pestañas de Modos estilo Hamara Tunnel (UDP | FastDNS | SuperDNS | DNSLite) ───
            var currentMode by remember { mutableStateOf(viewModel.prefs.selectedMode) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurfaceVariant)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("UDP", "FastDNS", "SuperDNS", "DNSLite").forEach { mode ->
                    val isSelected = currentMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) CyanPrimary.copy(alpha = 0.25f) else Color.Transparent)
                            .border(
                                width = if (isSelected) 1.dp else 0.dp,
                                color = if (isSelected) CyanPrimary else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                currentMode = mode
                                viewModel.onModeSelected(mode)
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode,
                            color = if (isSelected) CyanPrimary else TextMuted,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Botón Circular Principal de Conexión (Animado) ────────────────
            val isConnected = status == TunnelStatus.CONNECTED
            val isConnecting = status == TunnelStatus.CONNECTING || status == TunnelStatus.AUTHENTICATING

            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (isConnected || isConnecting) 1.08f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseScale"
            )

            val buttonColor by animateColorAsState(
                targetValue = when (status) {
                    TunnelStatus.CONNECTED -> StatusGreen
                    TunnelStatus.CONNECTING, TunnelStatus.AUTHENTICATING -> StatusOrange
                    TunnelStatus.PREEMPTED, TunnelStatus.ERROR -> StatusRed
                    else -> CyanPrimary
                },
                label = "buttonColor"
            )

            Box(
                modifier = Modifier
                    .size(150.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                buttonColor.copy(alpha = 0.35f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(buttonColor)
                        .clickable {
                            if (!viewModel.prefs.isLoggedIn) {
                                viewModel.openLoginDialog()
                            } else {
                                onRequestVpnPermission()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            color = TextDark,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(42.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Conectar",
                            tint = TextDark,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = when (status) {
                    TunnelStatus.CONNECTED -> "CONECTADO"
                    TunnelStatus.CONNECTING -> "CONECTANDO..."
                    TunnelStatus.AUTHENTICATING -> "AUTENTICANDO..."
                    TunnelStatus.DISCONNECTING -> "DESCONECTANDO..."
                    TunnelStatus.PREEMPTED -> "DESCONECTADO (OTRO DISPOSITIVO)"
                    TunnelStatus.ERROR -> "ERROR"
                    else -> "DESCONECTADO"
                },
                color = buttonColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(18.dp))

            // ── Métricas de Tráfico en Tiempo Real ────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurface)
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                // Download
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "%.1f KB/s".format(stats.speedInKbps),
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Text(
                        text = formatBytes(stats.bytesIn),
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                // Upload
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Upload,
                            contentDescription = null,
                            tint = AmberAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "%.1f KB/s".format(stats.speedOutKbps),
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Text(
                        text = formatBytes(stats.bytesOut),
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                // Duración
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatDuration(stats.durationSeconds),
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Tiempo",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Consola de Logs en Tiempo Real (Estilo Hamara Tunnel) ─────────
            Text(
                text = "LOGS DEL TÚNEL",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(6.dp))

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(
                    state = logListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                ) {
                    items(logs) { entry ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                text = "[${entry.timestamp}] ",
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            Text(
                                text = entry.message,
                                color = when {
                                    entry.isError -> StatusRed
                                    entry.isHighlight -> CyanPrimary
                                    else -> TextWhite
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        // ── Diálogo de Login Modal ───────────────────────────────────────────
        if (showLogin) {
            LoginDialog(
                initialUsername = viewModel.prefs.username,
                initialPassword = viewModel.prefs.password,
                rememberCredentials = viewModel.prefs.rememberCredentials,
                isLoading = isLoggingIn,
                errorMessage = loginError,
                isDeviceLimitReached = isDeviceLimitReached,
                onDismiss = { viewModel.dismissLoginDialog() },
                onLogin = { u, p, rem, force -> viewModel.login(u, p, rem, force) }
            )
        }

        // ── Sheets de Selección ──────────────────────────────────────────────
        if (showServerSheet) {
            ServerSelectorSheet(
                servers = servers,
                selectedServer = selectedServer,
                onServerSelected = { viewModel.selectServer(it) },
                onDismiss = { showServerSheet = false }
            )
        }

        if (showTweakSheet) {
            TweakSelectorSheet(
                tweaks = tweaks,
                selectedTweak = selectedTweak,
                onTweakSelected = { viewModel.selectTweak(it) },
                onDismiss = { showTweakSheet = false }
            )
        }

        if (showCustomSniDialog) {
            CustomSniDialog(
                currentSni = customSni,
                onDismiss = { showCustomSniDialog = false },
                onSaveSni = { viewModel.updateCustomSni(it) }
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024f * 1024f * 1024f))
        bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024f * 1024f))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }
}

private fun formatDuration(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) {
        "%02d:%02d:%02d".format(hrs, mins, secs)
    } else {
        "%02d:%02d".format(mins, secs)
    }
}
