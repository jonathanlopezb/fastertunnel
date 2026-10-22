package com.fastertunnel.app.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastertunnel.app.R
import com.fastertunnel.app.core.vpn.TunnelStatus
import com.fastertunnel.app.ui.theme.CyanPrimary
import com.fastertunnel.app.ui.theme.DarkBackground
import com.fastertunnel.app.ui.theme.DarkSurface
import com.fastertunnel.app.ui.theme.DarkSurfaceVariant
import com.fastertunnel.app.ui.theme.StatusGreen
import com.fastertunnel.app.ui.theme.StatusOrange
import com.fastertunnel.app.ui.theme.StatusRed
import com.fastertunnel.app.ui.theme.TextDark
import com.fastertunnel.app.ui.theme.TextMuted
import com.fastertunnel.app.ui.theme.TextWhite
import com.fastertunnel.app.ui.viewmodel.MainViewModel

@Composable
fun TunnelHomeScreen(
    viewModel: MainViewModel,
    onRequestVpnPermission: () -> Unit,
    onImportProfile: () -> Unit
) {
    val status by viewModel.tunnelStatus.collectAsState()
    val stats by viewModel.trafficStats.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val profileUri by viewModel.wireGuardProfileUri.collectAsState()
    val hasProfile = !profileUri.isNullOrBlank()

    val statusColor = when (status) {
        TunnelStatus.CONNECTED -> StatusGreen
        TunnelStatus.CONNECTING, TunnelStatus.AUTHENTICATING -> StatusOrange
        TunnelStatus.ERROR, TunnelStatus.PREEMPTED -> StatusRed
        else -> TextMuted
    }
    val isConnecting = status == TunnelStatus.CONNECTING ||
        status == TunnelStatus.AUTHENTICATING
    val isConnected = status == TunnelStatus.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF101B2B), DarkBackground, DarkBackground)
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "FasterTunnel",
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "FasterTunnel",
                    color = TextWhite,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "VPN privada • sin cuenta",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = "WireGuard",
                tint = CyanPrimary,
                modifier = Modifier.size(25.dp)
            )
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(statusColor.copy(alpha = 0.12f))
                .border(1.dp, statusColor.copy(alpha = 0.32f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when (status) {
                    TunnelStatus.CONNECTED -> "PROTECCIÓN ACTIVA"
                    TunnelStatus.CONNECTING, TunnelStatus.AUTHENTICATING -> "CONECTANDO"
                    TunnelStatus.DISCONNECTING -> "DESCONECTANDO"
                    TunnelStatus.ERROR -> "NO SE PUDO CONECTAR"
                    TunnelStatus.PREEMPTED -> "DESCONECTADO"
                    else -> "LISTO PARA CONECTAR"
                },
                color = statusColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }

        Spacer(Modifier.height(22.dp))

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onImportProfile)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(CyanPrimary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (hasProfile) Icons.Default.CheckCircle else Icons.Default.UploadFile,
                        contentDescription = null,
                        tint = if (hasProfile) StatusGreen else CyanPrimary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (hasProfile) "Perfil WireGuard listo" else "Conexión automática",
                        color = TextWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (hasProfile) "Perfil seguro seleccionado" else "El perfil se configura al conectar",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Cambiar perfil",
                    tint = TextMuted,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        Spacer(Modifier.height(26.dp))

        Box(
            modifier = Modifier
                .size(206.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            (if (isConnected) StatusGreen else CyanPrimary).copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    )
                )
                .padding(15.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = if (isConnected) {
                                listOf(Color(0xFF46E6A6), Color(0xFF16B77D))
                            } else {
                                listOf(Color(0xFF25D9F2), Color(0xFF0B9DBA))
                            }
                        )
                    )
                    .clickable(enabled = !isConnecting, onClick = onRequestVpnPermission),
                contentAlignment = Alignment.Center
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        color = TextDark,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(48.dp)
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = if (isConnected) "Desconectar" else "Conectar",
                            tint = TextDark,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (isConnected) "DESCONECTAR" else "CONECTAR",
                            color = TextDark,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = when (status) {
                TunnelStatus.CONNECTED -> "Conexión cifrada y verificada"
                TunnelStatus.CONNECTING, TunnelStatus.AUTHENTICATING -> "Estableciendo túnel seguro…"
                TunnelStatus.ERROR -> "Revisa el registro para ver la causa"
                else -> if (hasProfile) "Toca para iniciar tu conexión" else "Toca para obtener el perfil y conectar"
            },
            color = TextMuted,
            fontSize = 13.sp
        )

        if (isConnected) {
            Spacer(Modifier.height(18.dp))
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TrafficValue("↓", formatBytes(stats.bytesIn), "Recibidos")
                    TrafficValue("↑", formatBytes(stats.bytesOut), "Enviados")
                    TrafficValue("", formatDuration(stats.durationSeconds), "Duración")
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = CyanPrimary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = "Tráfico cifrado con WireGuard",
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "REGISTRO",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }

        Spacer(Modifier.height(8.dp))
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp)
        ) {
            if (logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Los eventos de conexión aparecerán aquí.", color = TextMuted, fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    reverseLayout = true
                ) {
                    items(logs.asReversed()) { entry ->
                        Text(
                            text = "${entry.timestamp}  ${entry.message}",
                            color = when {
                                entry.isError -> StatusRed
                                entry.isHighlight -> CyanPrimary
                                else -> TextWhite
                            },
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrafficValue(valueIcon: String, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (valueIcon.isEmpty()) value else "$valueIcon  $value",
            color = TextWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Text(text = label, color = TextMuted, fontSize = 10.sp)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024f * 1024f * 1024f))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    val hours = mins / 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, mins % 60, secs)
    } else {
        "%02d:%02d".format(mins, secs)
    }
}
