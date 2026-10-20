package com.fastertunnel.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.fastertunnel.app.ui.screens.MainTunnelScreen
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
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FasterTunnelTheme {
                MainTunnelScreen(
                    viewModel = viewModel,
                    onRequestVpnPermission = { requestVpnPermissionAndConnect() }
                )
            }
        }
    }

    private fun requestVpnPermissionAndConnect() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            // Ya tiene permiso concedido
            viewModel.toggleTunnel(this)
        }
    }
}
