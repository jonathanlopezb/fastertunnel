package com.fastertunnel.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.fastertunnel.app.core.vpn.TunnelLogManager
import com.fastertunnel.app.ui.screens.TunnelHomeScreen
import com.fastertunnel.app.ui.theme.FasterTunnelTheme
import com.fastertunnel.app.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Launcher para solicitar permiso del sistema para VpnService
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.toggleTunnel(this)
        } else {
            TunnelLogManager.log("Permiso VPN denegado por el usuario.", isError = true)
        }
    }

    private val profilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                viewModel.setWireGuardProfile(uri)
            } catch (e: SecurityException) {
                TunnelLogManager.logException("No se pudo guardar el acceso al perfil", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        getSystemService(NotificationManager::class.java).cancel(1001)

        setContent {
            FasterTunnelTheme {
                TunnelHomeScreen(
                    viewModel = viewModel,
                    onRequestVpnPermission = { requestVpnPermissionAndConnect() },
                    onImportProfile = { profilePicker.launch(arrayOf("*/*")) }
                )
            }
        }
    }

    private fun requestVpnPermissionAndConnect() {
        if (viewModel.wireGuardProfileUri.value.isNullOrBlank()) {
            profilePicker.launch(arrayOf("*/*"))
            return
        }
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            // Ya tiene permiso concedido
            viewModel.toggleTunnel(this)
        }
    }
}
