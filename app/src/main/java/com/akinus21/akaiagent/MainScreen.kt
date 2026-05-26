package com.akinus21.akaiagent

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val hasConfig = viewModel.hasSavedConfig()

    var queueUrl by remember { mutableStateOf(viewModel.savedQueueUrl.ifEmpty { "https://ollama.akinus21.com" }) }
    var username by remember { mutableStateOf(viewModel.savedUsername) }
    var expandedLog by remember { mutableStateOf(false) }

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
                        value = queueUrl,
                        onValueChange = { queueUrl = it },
                        label = { Text("Queue URL") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state !is WorkerState.Initializing
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state !is WorkerState.Initializing
                    )

                    Button(
                        onClick = { viewModel.initAndStart(queueUrl, username) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = queueUrl.isNotBlank() && username.isNotBlank()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Initialize & Start")
                    }

                    if (hasConfig && state is WorkerState.Idle) {
                        OutlinedButton(
                            onClick = { viewModel.startWithSavedConfig() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Start with Saved Config")
                        }
                    }
                }

                is WorkerState.Initializing -> {
                    CircularProgressIndicator()
                    Text("Authenticating with queue...", style = MaterialTheme.typography.bodyMedium)
                }

                is WorkerState.FetchingCerts -> {
                    CircularProgressIndicator()
                    Text("Fetching tunnel certificates...", style = MaterialTheme.typography.bodyMedium)
                }

                is WorkerState.StartingRpc -> {
                    CircularProgressIndicator()
                    Text("Starting rpc-server...", style = MaterialTheme.typography.bodyMedium)
                }

                is WorkerState.Connecting -> {
                    CircularProgressIndicator()
                    Text("Connecting tunnel...", style = MaterialTheme.typography.bodyMedium)
                }

                is WorkerState.Running -> {
                    val host = (state as WorkerState.Running).host
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
                                "Tunnel: $host",
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