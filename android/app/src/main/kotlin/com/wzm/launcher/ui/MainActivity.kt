package com.wzm.launcher.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wzm.launcher.server.ServerStatus
import com.wzm.launcher.ui.theme.WzmLauncherTheme
import com.wzm.launcher.wzm.WzmStatus

class MainActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WzmLauncherTheme {
                LauncherScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshWzmStatus()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(vm: LauncherViewModel) {
    val state by vm.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) listState.animateScrollToItem(state.logs.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WZM Offline Launcher", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status cards
            StatusCard(
                title = "Servidor",
                status = when (state.serverStatus) {
                    ServerStatus.PARADO -> "PARADO"
                    ServerStatus.INICIANDO -> "INICIANDO"
                    ServerStatus.ONLINE -> "ONLINE (${state.serverStatus})"
                    ServerStatus.PARANDO -> "PARANDO"
                    ServerStatus.ERRO -> "ERRO"
                },
                color = when (state.serverStatus) {
                    ServerStatus.ONLINE -> Color(0xFF79C370)
                    ServerStatus.ERRO -> Color(0xFFC13D5F)
                    ServerStatus.INICIANDO, ServerStatus.PARANDO -> Color(0xFFFFC107)
                    else -> Color(0xFF9AA39A)
                },
                detail = "127.0.0.1:18081 • /health = 200 OK (stub)"
            )
            // WZM status
            WzmStatusCard(state)

            // Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.startServer() },
                    enabled = state.serverStatus == ServerStatus.PARADO || state.serverStatus == ServerStatus.ERRO,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF79C370))
                ) { Text("INICIAR SERVIDOR") }
                Button(
                    onClick = { vm.stopServer() },
                    enabled = state.serverStatus == ServerStatus.ONLINE,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C757D))
                ) { Text("PARAR SERVIDOR") }
            }
            Button(
                onClick = { vm.launchWzm() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007BFF))
            ) { Text("INICIAR WARZONE MOBILE") }

            Text("Logs", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    items(state.logs) { log ->
                        Text(log, color = Color(0xFFE0E0E0), fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }

            Text(
                "Launcher MVP 0.1.0 — servidor stub em 127.0.0.1:18081; CDNI real não implementado.",
                color = Color(0xFF9AA39A), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
fun StatusCard(title: String, status: String, color: Color, detail: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Color(0xFF9AA39A), fontSize = 12.sp)
            Text(status, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(detail, color = Color(0xFF6C757D), fontSize = 11.sp)
        }
    }
}

@Composable
fun WzmStatusCard(state: LauncherUiState) {
    val (label, color) = when (state.wzmStatus) {
        WzmStatus.NAO_DETECTADO -> "NÃO DETECTADO" to Color(0xFFC13D5F)
        WzmStatus.INSTALADO -> "INSTALADO${state.wzmVersion?.let { " • $it" } ?: ""}" to Color(0xFF79C370)
        WzmStatus.INICIANDO -> "INICIANDO" to Color(0xFFFFC107)
        WzmStatus.EXECUTANDO -> "EXECUTANDO" to Color(0xFF79C370)
        WzmStatus.NAO_ESTA_EXECUTANDO -> "NÃO ESTÁ EXECUTANDO" to Color(0xFF9AA39A)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Warzone Mobile", color = Color(0xFF9AA39A), fontSize = 12.sp)
            Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("com.activision.callofduty.warzone", color = Color(0xFF6C757D), fontSize = 11.sp)
        }
    }
}
