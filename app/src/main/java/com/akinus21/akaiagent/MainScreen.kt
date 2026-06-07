package com.akinus21.akaiagent

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val hasConfig = viewModel.hasSavedConfig()

    var apiUrl by remember { mutableStateOf(viewModel.savedApiUrl.ifEmpty { "http://akai-net.akinus21.com" }) }
    var username by remember { mutableStateOf(viewModel.savedUsername) }
    var showDirectConnect by remember { mutableStateOf(false) }
    var directHubAddr by remember { mutableStateOf("akai-net.akinus21.com:50051") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("akai-agent") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Distributed Inference Worker",
                style = MaterialTheme.typography.headlineSmall
            )

            when (state) {
                is WorkerState.Idle, is WorkerState.Error -> {
                    if (state is WorkerState.Error) {
                        Text(
                            (state as WorkerState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    OutlinedTextField(
                        value = apiUrl,
                        onValueChange = { apiUrl = it },
                        label = { Text("Hub API URL") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state !is WorkerState.EnrollingVpn
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state !is WorkerState.EnrollingVpn
                    )

                    Button(
                        onClick = { viewModel.initAndStart(apiUrl, username) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = apiUrl.isNotBlank() && username.isNotBlank()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Enroll & Start")
                    }

                    if (hasConfig && state is WorkerState.Idle) {
                        OutlinedButton(
                            onClick = { viewModel.startWithSavedConfig() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Start with Saved Config")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    TextButton(
                        onClick = { showDirectConnect = !showDirectConnect },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (showDirectConnect) "Hide direct connect" else "Direct connect (no VPN)")
                    }

                    if (showDirectConnect) {
                        OutlinedTextField(
                            value = directHubAddr,
                            onValueChange = { directHubAddr = it },
                            label = { Text("Hub address (host:port)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                if (username.isNotBlank()) {
                                    viewModel.directConnect(directHubAddr, username)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = directHubAddr.isNotBlank() && username.isNotBlank()
                        ) {
                            Text("Connect Directly")
                        }
                    }
                }

                is WorkerState.EnrollingVpn -> {
                    CircularProgressIndicator()
                    Text("Enrolling VPN...", style = MaterialTheme.typography.bodyMedium)
                }

                is WorkerState.EnrollingVpnFailed -> {
                    Text("VPN enrollment failed. Check network and try again.", color = MaterialTheme.colorScheme.error)
                }

                is WorkerState.StartingWorker -> {
                    CircularProgressIndicator()
                    Text("Starting worker...", style = MaterialTheme.typography.bodyMedium)
                }

                is WorkerState.Running -> {
                    val hubAddr = (state as WorkerState.Running).hubAddr
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Connected", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Hub: $hubAddr",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Button(
                        onClick = { viewModel.stopWorker() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop Worker")
                    }
                }
            }
        }
    }
}