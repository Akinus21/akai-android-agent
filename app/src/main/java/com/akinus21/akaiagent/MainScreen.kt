package com.akinus21.akaiagent

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("akai_agent", 0)

    var queueUrl by remember { mutableStateOf(prefs.getString("queue_url", "") ?: "") }
    var username by remember { mutableStateOf(prefs.getString("username", "") ?: "") }
    var workerId by remember { mutableStateOf(android.os.Build.MODEL.replace(" ", "-").lowercase()) }

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

            OutlinedTextField(
                value = queueUrl,
                onValueChange = { queueUrl = it },
                label = { Text("Queue URL") },
                modifier = Modifier.fillMaxWidth(),
                enabled = state is WorkerState.Idle || state is WorkerState.Error
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                enabled = state is WorkerState.Idle || state is WorkerState.Error
            )

            OutlinedTextField(
                value = workerId,
                onValueChange = { workerId = it },
                label = { Text("Worker ID") },
                modifier = Modifier.fillMaxWidth(),
                enabled = state is WorkerState.Idle || state is WorkerState.Error
            )

            when (state) {
                is WorkerState.Idle, is WorkerState.Error -> {
                    Button(
                        onClick = { viewModel.init(queueUrl, username) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = queueUrl.isNotBlank() && username.isNotBlank()
                    ) {
                        Text("Initialize & Connect")
                    }
                }
                is WorkerState.Initializing -> {
                    CircularProgressIndicator()
                    Text("Authenticating...")
                }
                is WorkerState.Connected -> {
                    val host = (state as WorkerState.Connected).host
                    Button(
                        onClick = {
                            viewModel.startWorker(
                                "tunnel.akinus21.com", 443, workerId, 50052
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start Worker")
                    }
                    Button(
                        onClick = { viewModel.stopWorker() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Stop Worker")
                    }
                }
            }

            if (state is WorkerState.Error) {
                Text(
                    (state as WorkerState.Error).message,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}