package com.wzm.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wzm.launcher.cdn.RequestLog
import kotlinx.coroutines.launch

/** Filtro "todos" (nenhuma tag específica). */
private const val FILTER_ALL = "TODOS"

/** Tags conhecidas do roteador (aparecem como chips, mesmo antes de existir linha com elas). */
private val KNOWN_TAGS = listOf("DNS", "CDNI", "CDNI?", "HTTP", "TLS", "TUN", "VPN", "LAUNCHER")

/**
 * Tela "VER LOGS": RequestLog completo do roteador CDNI, em tempo real, dentro do APK
 * (sem ADB/Logcat). Ver `docs/launcher.md` §5.2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var filter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    val lines = remember(state.logs, filter) {
        RequestLog.filterTags(state.logs, if (filter == FILTER_ALL) null else filter)
    }

    // Atualização em tempo real (o WZM pode estar aberto em primeiro plano).
    LaunchedEffect(lines.size, autoScroll, filter) {
        if (autoScroll && lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
    }

    BackHandler { onBack() }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Limpar todo o log?") },
            text = { Text("As linhas e os contadores são apagados (inclusive o arquivo persistido). Use COPIAR/SALVAR antes se quiser guardar.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearLog()
                    scope.launch { snackbarHostState.showSnackbar("Log limpo") }
                }) { Text("LIMPAR") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("CANCELAR") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("RequestLog (CDNI local)", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1E1E),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ---- contadores (DNS, conexões TCP, requests, TLS) ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Contadores", color = Color(0xFF9AA39A), fontSize = 11.sp)
                    Text(state.counters.summary(), color = Color(0xFFB0E0B0), fontSize = 11.sp)
                    Text(
                        "arquivo: ${logFilePathOf(vm)} • buffer: ${state.logs.size}/${RequestLog.MAX_ENTRIES} linhas",
                        color = Color(0xFF6C757D),
                        fontSize = 10.sp
                    )
                }
            }

            // ---- filtros por tag + auto-scroll ----
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                (listOf(FILTER_ALL) + KNOWN_TAGS).forEach { tag ->
                    FilterChip(
                        selected = filter == tag,
                        onClick = { filter = tag },
                        label = { Text(tag, fontSize = 10.sp) }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${lines.size} linha(s)${if (filter == FILTER_ALL) "" else " com tag [$filter]"}",
                    color = Color(0xFF6C757D),
                    fontSize = 11.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("auto-rolar", color = Color(0xFF9AA39A), fontSize = 11.sp)
                    Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
                }
            }

            // ---- log em tempo real ----
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141414))
            ) {
                if (lines.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (state.logs.isEmpty()) "Sem eventos ainda." else "Nenhuma linha com a tag [$filter].",
                            color = Color(0xFF9AA39A),
                            fontSize = 12.sp
                        )
                        Text(
                            "Inicie o ROTEADOR CDNI e depois o WZM para ver [DNS], [CDNI], [TLS] e [HTTP] aqui.",
                            color = Color(0xFF6C757D),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    ) {
                        items(lines) { line ->
                            Text(
                                text = line,
                                color = logColor(line),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            // ---- ações ----
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { confirmClear = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C757D))
                ) { Text("LIMPAR LOGS", fontSize = 11.sp) }
                Button(
                    onClick = {
                        copyToClipboard(context, vm.logTextForClipboard())
                        scope.launch {
                            snackbarHostState.showSnackbar("Log copiado (${state.logs.size} linhas)")
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007BFF))
                ) { Text("COPIAR LOGS", fontSize = 11.sp) }
            }
            OutlinedButton(
                onClick = {
                    val file = vm.saveLogFile()
                    if (file == null) {
                        scope.launch { snackbarHostState.showSnackbar("Falha ao salvar o log") }
                    } else {
                        scope.launch { snackbarHostState.showSnackbar("Log salvo: ${file.absolutePath}") }
                        val share = vm.shareLogIntent(file)
                        if (share != null) {
                            runCatching { context.startActivity(share) }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("SALVAR / EXPORTAR .TXT", fontSize = 11.sp) }

            Text(
                "Sem dados sensíveis: registramos tag, horário, host/path, método e status — nunca corpos de " +
                    "requisição nem cabeçalhos (sem cookies/tokens).",
                color = Color(0xFF6C757D),
                fontSize = 9.sp
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("WZM RequestLog", text))
}

/** Caminho do arquivo persistido (mostrado para o usuário achar o log). */
private fun logFilePathOf(vm: LauncherViewModel): String =
    vm.logFilePath() ?: "(memória)"

/** Cor por tag (compartilhada entre a tela principal e a tela de logs). */
fun logColor(line: String): Color = when (RequestLog.tagOf(line)) {
    "CDNI?" -> Color(0xFFFF9F9F)
    "CDNI", "DNS", "HTTP" -> Color(0xFF9FE0A0)
    "TLS" -> Color(0xFFFFD79F)
    "TUN", "VPN" -> Color(0xFF9FC7FF)
    else -> Color(0xFFE0E0E0)
}

/** Reaproveitado pela tela principal no preview do log. */
fun previewLogLines(lines: List<String>, max: Int): List<String> = lines.takeLast(max)
