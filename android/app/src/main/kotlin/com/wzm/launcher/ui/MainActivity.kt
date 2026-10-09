package com.wzm.launcher.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wzm.launcher.cdn.RequestCounters
import com.wzm.launcher.cdn.CdnRouterStatus
import com.wzm.launcher.server.ServerStatus
import com.wzm.launcher.ui.theme.WzmLauncherTheme
import com.wzm.launcher.wzm.WzmStatus

/** Linhas mostradas no preview do log na tela principal (a tela VER LOGS mostra todas). */
private const val LOG_PREVIEW_MAX_LINES = 60

/**
 * Altura FIXA do painel de preview do log (M3.5.1).
 *
 * Antes o painel usava `weight(1f)` dentro de um `Column` **sem rolagem**: o relatório do teste
 * sintético aumentava a altura do conteúdo, o peso ficava com 0 dp de sobra e os controles seguintes
 * (INICIAR WARZONE MOBILE, VER LOGS, EXPORTAR CA) saíam da tela **sem forma de alcançá-los**.
 * Com altura fixa o painel não cresce nem empurra o resto; ele rola por dentro.
 */
private val LOG_PANEL_HEIGHT = 200.dp

class MainActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WzmLauncherTheme {
                // Navegação simples (sem lib): o RequestLog vive no singleton/ViewModel, então
                // alternar entre as telas não perde nada.
                var showLogs by rememberSaveable { mutableStateOf(false) }
                if (showLogs) {
                    LogsScreen(viewModel, onBack = { showLogs = false })
                } else {
                    LauncherScreen(viewModel, onOpenLogs = { showLogs = true })
                }
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
fun LauncherScreen(vm: LauncherViewModel, onOpenLogs: () -> Unit = {}) {
    val state by vm.uiState.collectAsState()
    val consentRequest by vm.consentRequest.collectAsState()

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vm.onVpnConsentResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(consentRequest) {
        val intent = consentRequest
        if (intent != null) {
            consentLauncher.launch(intent)
            vm.onConsentLaunched()
        }
    }

    LauncherContent(
        state = state,
        onStartRouter = { vm.startRouter() },
        onStopRouter = { vm.stopRouter() },
        onSyntheticTest = { vm.runSyntheticTest() },
        onLaunchWzm = { vm.launchWzm() },
        onOpenLogs = onOpenLogs,
        onInstallCa = { vm.installCa() }
    )
}

/**
 * Conteúdo da tela principal **sem dependência do ViewModel** (M3.5.1) — assim o layout pode ser
 * verificado em teste de UI na JVM (Robolectric), sem VPN/ADB/device.
 *
 * M3.5.1 (hotfix de UI): todo o conteúdo vive num ÚNICO `LazyColumn` rolável. Nenhuma lógica de rede,
 * CDNI, TLS, DNS ou RequestLog foi tocada — só o container e a altura do painel de log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherContent(
    state: LauncherUiState,
    onStartRouter: () -> Unit = {},
    onStopRouter: () -> Unit = {},
    onSyntheticTest: () -> Unit = {},
    onLaunchWzm: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onInstallCa: () -> Unit = {}
) {
    val previewListState = rememberLazyListState()
    var logPanelExpanded by rememberSaveable { mutableStateOf(true) }
    val previewLines = remember(state.logs) { previewLogLines(state.logs, LOG_PREVIEW_MAX_LINES) }

    // Auto-rolagem apenas do preview, com índice limitado ao que é realmente exibido
    // (`logs.size - 1` podia apontar para fora da lista de 60 linhas).
    LaunchedEffect(previewLines.size, logPanelExpanded) {
        if (logPanelExpanded && previewLines.isNotEmpty()) {
            previewListState.scrollToItem(previewLines.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // Nome exibido ao usuário (o applicationId continua com.wzm.launcher).
                title = { Text("Project Rezone", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1E1E),
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
                .padding(padding)
                .testTag(LauncherTestTags.CONTENT),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                StatusCard(
                    title = "Servidor stub (M2)",
                    status = when (state.serverStatus) {
                        ServerStatus.PARADO -> "PARADO"
                        ServerStatus.INICIANDO -> "INICIANDO"
                        ServerStatus.ONLINE -> "ONLINE"
                        ServerStatus.PARANDO -> "PARANDO"
                        ServerStatus.ERRO -> "ERRO"
                    },
                    color = when (state.serverStatus) {
                        ServerStatus.ONLINE -> Color(0xFF79C370)
                        ServerStatus.ERRO -> Color(0xFFC13D5F)
                        ServerStatus.INICIANDO, ServerStatus.PARANDO -> Color(0xFFFFC107)
                        ServerStatus.PARADO -> Color(0xFF9AA39A)
                    },
                    detail = "127.0.0.1:18081 • /health (protótipo antigo, não é o CDNI)"
                )
            }

            item { RouterCard(state.router, state.counters) }

            item { WzmStatusCard(state) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onStartRouter,
                        enabled = !state.router.httpsRunning || !state.router.vpnActive,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF79C370))
                    ) { Text("INICIAR ROTEADOR CDNI", fontSize = 12.sp) }
                    Button(
                        onClick = onStopRouter,
                        enabled = state.router.httpsRunning || state.router.vpnActive,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C757D))
                    ) { Text("PARAR ROTEADOR", fontSize = 12.sp) }
                }
            }

            item {
                OutlinedButton(
                    onClick = onSyntheticTest,
                    enabled = state.router.httpsRunning,
                    modifier = Modifier.fillMaxWidth().testTag(LauncherTestTags.SYNTHETIC_BUTTON)
                ) { Text("TESTE SINTÉTICO DNS → 10.111.222.1:443 → cdni.meta", fontSize = 11.sp) }
            }

            state.syntheticReport?.let { report ->
                item { SyntheticReportCard(report) }
            }

            // M4.1: resultado do teste de controle de autoria (só aparece depois de rodar no device).
            if (state.counters.ownerProbeResumo.isNotBlank()) {
                item { OwnerProbeCard(state.counters.ownerProbeResumo) }
            }

            item {
                Button(
                    onClick = onLaunchWzm,
                    modifier = Modifier.fillMaxWidth().testTag(LauncherTestTags.LAUNCH_WZM),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007BFF))
                ) { Text("INICIAR WARZONE MOBILE") }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag(LauncherTestTags.ACTION_ROW)
                ) {
                    Button(
                        onClick = onOpenLogs,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F4F2F))
                    ) { Text("VER LOGS", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = onInstallCa,
                        modifier = Modifier.weight(1f)
                    ) { Text("EXPORTAR CA LOCAL", fontSize = 11.sp) }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Log (launcher + CDNI)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.logs.size} linhas", color = Color(0xFF6C757D), fontSize = 11.sp)
                        TextButton(
                            onClick = { logPanelExpanded = !logPanelExpanded },
                            modifier = Modifier.testTag(LauncherTestTags.LOG_TOGGLE)
                        ) { Text(if (logPanelExpanded) "RECOLHER" else "MOSTRAR", fontSize = 11.sp) }
                    }
                }
            }

            if (logPanelExpanded) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LOG_PANEL_HEIGHT)
                            .testTag(LauncherTestTags.LOG_PANEL),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                    ) {
                        if (previewLines.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Sem eventos ainda — inicie o roteador CDNI.",
                                    color = Color(0xFF9AA39A),
                                    fontSize = 11.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                state = previewListState,
                                modifier = Modifier.fillMaxSize().padding(8.dp)
                            ) {
                                items(previewLines) { log ->
                                    Text(
                                        log,
                                        color = logColor(log),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "M3: DNS + HTTPS locais roteiam prod.cdni.callofduty.com para o servidor embarcado; " +
                        "requests reais do WZM aparecem em VER LOGS. Certificado local é NOSSO (não é da Activision).",
                    color = Color(0xFF9AA39A),
                    fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Relatório do teste sintético (M3.5) — o texto é longo, então vive num card próprio e rolável. */
@Composable
fun SyntheticReportCard(report: String) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(LauncherTestTags.SYNTHETIC_REPORT),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Teste sintético do caminho CDNI", color = Color(0xFF9AA39A), fontSize = 12.sp)
            Text(report, color = Color(0xFFD7C36B), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * Teste de controle de autoria (M4.1): mostra o que a API pública `getConnectionOwnerUid` respondeu
 * para conexões do próprio launcher (loopback e endereço do túnel) e para uma tupla inexistente.
 *
 * Leitura correta: só o passo resolvido prova autoria; `INVALID_UID` é **ambíguo por desenho**
 * (socket não encontrado OU fora do per-app da VPN) e não pode ser lido como "não é o jogo".
 */
@Composable
fun OwnerProbeCard(resumo: String) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(LauncherTestTags.OWNER_PROBE),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2230))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Teste de controle de autoria (M4.1) — getConnectionOwnerUid",
                color = Color(0xFF9AA39A),
                fontSize = 12.sp
            )
            Text(resumo, color = Color(0xFF8EB6E0), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun RouterCard(router: CdnRouterStatus, counters: RequestCounters) {
    val httpsLabel = when {
        router.httpsRunning -> "ATIVO (${router.endpoints})"
        router.serverError != null -> "ERRO: ${router.serverError}"
        else -> "PARADO"
    }
    val httpsColor = when {
        router.httpsRunning -> Color(0xFF79C370)
        router.serverError != null -> Color(0xFFC13D5F)
        else -> Color(0xFF9AA39A)
    }
    val vpnLabel = when {
        router.vpnActive -> "ATIVO"
        router.vpnError != null -> "ERRO: ${router.vpnError}"
        else -> "PARADO"
    }
    val vpnColor = when {
        router.vpnActive -> Color(0xFF79C370)
        router.vpnError != null -> Color(0xFFC13D5F)
        else -> Color(0xFF9AA39A)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Roteador CDNI local", color = Color(0xFF9AA39A), fontSize = 12.sp)
            // Fase explícita (M3.4): não existe "pronto" enquanto o listener do túnel não subir.
            Text("Fase — ${router.phaseLabel}", color = Color(0xFFD7C36B), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("HTTPS :443 — $httpsLabel", color = httpsColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("Túnel DNS — $vpnLabel", color = vpnColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (!router.tunnelBound) {
                Text(
                    "Listener do túnel (10.111.222.1:443) ausente — só o caminho 127.0.0.1:443 atende " +
                        "(limitação registrada; ver docs M3.4)",
                    color = Color(0xFFC9A227),
                    fontSize = 11.sp
                )
            }
            Text(counters.summary(), color = Color(0xFFB0B8B0), fontSize = 11.sp)
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
