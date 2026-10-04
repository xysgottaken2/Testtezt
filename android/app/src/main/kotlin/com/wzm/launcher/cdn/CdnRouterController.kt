package com.wzm.launcher.cdn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Estado do roteador CDNI local (servidor HTTPS + túnel) exposto para a UI. */
data class CdnRouterStatus(
    val httpsRunning: Boolean = false,
    val endpoints: String = "",
    val serverError: String? = null,
    val vpnActive: Boolean = false,
    val vpnError: String? = null,
    /** Fase explícita do ciclo de vida (M3.4): nunca "pronto" sem o listener do túnel. */
    val phase: RouterPhase = RouterPhase.PARADO,
    /** Listener no endereço do túnel (10.111.222.1:443) ativo. */
    val tunnelBound: Boolean = false,
    /** `10.111.222.1` está atribuído a uma interface (tun0). */
    val tunnelAddressAssigned: Boolean = false,
    /** Motivo registrado quando o listener do túnel não é possível (limitação documentada). */
    val tunnelListenerMissingReason: String? = null,
    val phaseHistory: List<String> = emptyList()
) {
    val phaseLabel: String get() = phase.label
}

/**
 * Orquestra o roteamento local na ordem correta (M3.4):
 *
 * ```
 * startRouter -> VPN_STARTING -> (serviço espera o endereço do túnel de forma LIMITADA)
 *             -> VPN_READY -> LOCAL_SERVER_STARTING -> (bind com retry limitado)
 *             -> LOCAL_SERVER_READY -> ROUTER_READY (somente com o listener do túnel ativo)
 * ```
 *
 * O listener de loopback continua existindo, mas **separado**, como caminho de diagnóstico —
 * o roteador não é declarado pronto por causa dele.
 */
object CdnRouterController {

    private var server: LocalHttpsServer? = null

    /** Pacote alvo da sessão (usado pelo veredito de origem e pelo teste de controle). */
    @Volatile
    private var targetPackage: String = ""

    private val lifecycle = RouterLifecycle { line -> RequestLog.add("DIAG", line) }

    private val _status = MutableStateFlow(CdnRouterStatus())
    val status: StateFlow<CdnRouterStatus> = _status.asStateFlow()

    val currentPhase: RouterPhase get() = lifecycle.phase

    /**
     * Início correto do roteador: **VPN primeiro**; o servidor HTTPS só é iniciado depois que o
     * serviço confirma que `10.111.222.1` está atribuído (callback [onVpnEstablished]).
     */
    fun startRouter(context: Context, wzmPackage: String) {
        val app = context.applicationContext
        AndroidDiagnostics.remember(app)
        RequestLog.add(
            "LAUNCHER",
            "iniciando roteador CDNI local na ordem correta: VPN -> endereço do túnel -> listener :443"
        )
        targetPackage = wzmPackage
        lifecycle.onVpnStarting("pedido de túnel enviado ao sistema (per-app: $wzmPackage)")
        publishStatus()
        CdnVpnService.start(app, wzmPackage)
    }

    /** Chamado pelo serviço depois de estabelecer a VPN e esperar (limitado) o endereço do túnel. */
    fun onVpnEstablished(context: Context, addressAssigned: Boolean, waitedMs: Long) {
        val app = context.applicationContext
        lifecycle.onVpnReady(
            addressAssigned,
            "espera limitada de ${waitedMs} ms por ${CdnRouterConfig.VPN_ADDRESS}"
        )
        publishStatus()
        lifecycle.onLocalServerStarting(
            "iniciando listener em ${CdnRouterConfig.VPN_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT} " +
                "com retry limitado (${CdnRouterConfig.TUNNEL_BIND_ATTEMPTS} tentativas)"
        )
        publishStatus()
        // O bind pode esperar alguns milissegundos pelo endereço: nunca na thread principal.
        Thread({ startServerBlocking(app) }, "cdn-https-start").also { it.isDaemon = true; it.start() }
    }

    private fun startServerBlocking(context: Context) {
        val material = try {
            TlsContextFactory.FromBytes(
                readAsset(context, CdnRouterConfig.CERT_ASSET),
                CdnRouterConfig.CERT_PASSWORD.toCharArray()
            )
        } catch (e: Exception) {
            val message = "asset ${CdnRouterConfig.CERT_ASSET} indisponível: ${e.javaClass.simpleName}: ${e.message}"
            RequestLog.add("CDNI", "FALHA: $message")
            lifecycle.onError(message)
            publishStatus(serverError = message)
            return
        }
        val appContext = context.applicationContext
        val targetUid = targetPackage.takeIf { it.isNotEmpty() }
            ?.let { AndroidDiagnostics.targetUid(appContext, it) }
        val created = LocalHttpsServer(
            tlsMaterial = material,
            ownerDescription = { socket -> AndroidDiagnostics.connectionOwner(appContext, socket) },
            originReport = { socket, role ->
                AndroidDiagnostics.loopbackOriginFacts(
                    appContext,
                    socket,
                    roleLoopback = role == LocalHttpsServer.EndpointRole.LOOPBACK_DIAGNOSTICO,
                    targetUid = targetUid
                )?.let { facts -> LoopbackOrigin.report(facts) }
            }
        )
        val started = created.start()
        if (started) {
            server = created
        }
        val endpoints = created.boundEndpoints.joinToString(", ") { it.toString() }
        val tunnelBound = created.boundEndpoints.any { it.role == LocalHttpsServer.EndpointRole.TUNEL_PRIMARIO }
        val loopbackBound = created.boundEndpoints.any {
            it.role == LocalHttpsServer.EndpointRole.LOOPBACK_DIAGNOSTICO
        }
        lifecycle.onLocalServerReady(tunnelBound, endpoints)
        val ready = if (tunnelBound) lifecycle.onRouterReady() else false
        publishStatus(
            httpsRunning = started,
            endpoints = endpoints,
            serverError = if (started) null else "nenhum listener HTTPS ativo (ver log)",
            tunnelBound = tunnelBound
        )
        RequestLog.add(
            "DIAG",
            "servidor local: listeners=[$endpoints] tunel=$tunnelBound loopback=$loopbackBound " +
                "fase=${lifecycle.phase.label}" +
                (if (!ready) " — ROUTER_READY não declarado (listener do túnel ausente)" else "")
        )
        // M4.1: teste de controle de autoria — só faz sentido com o listener no ar.
        runOwnerControlProbe(appContext, created)
    }

    /**
     * Teste de controle do item 2 do M4.1 (executado uma vez por sessão, com socket de verdade):
     * mede o que `getConnectionOwnerUid` devolve para conexões do **próprio launcher** em loopback e
     * no endereço do túnel, e para uma tupla que não existe. Nada sai do aparelho.
     */
    private fun runOwnerControlProbe(context: Context, server: LocalHttpsServer) {
        RequestLog.add(
            "DIAG",
            "teste de controle de autoria (M4.1): mede o que getConnectionOwnerUid devolve para conexões " +
                "do PRÓPRIO launcher (loopback e endereço do túnel) e para uma tupla inexistente"
        )
        val loopbackPort = server.boundEndpoints
            .firstOrNull { it.role == LocalHttpsServer.EndpointRole.LOOPBACK_DIAGNOSTICO }?.port
        val report = OwnerProbe.run(
            loopbackPort = loopbackPort,
            tunnelAddress = CdnRouterConfig.VPN_ADDRESS,
            tunnelPort = CdnRouterConfig.LOCAL_HTTPS_PORT,
            tunnelAddressAssigned = AndroidDiagnostics.isAddressAssigned(),
            selfPortRegistrar = { port -> SelfPorts.register(port) },
            connect = { host, port ->
                Socket().also { socket ->
                    socket.connect(InetSocketAddress(host, port), 2_000)
                    socket.soTimeout = 2_000
                }
            },
            querySocket = { socket -> ConnectionOwnership.querySocket(context, socket) },
            queryTuple = { protocol, first, second -> ConnectionOwnership.query(context, protocol, first, second) }
        )
        for (step in report.steps) {
            step.result?.let { RequestLog.incOwnerProbeResult(it) }
        }
        val linhas = report.lines()
        linhas.forEach { line -> RequestLog.add("DIAG", line) }
        val resumo = OwnerProbe.summaryLine(report)
        val leitura = "leitura do teste de controle: ${report.expectation()}"
        RequestLog.add("DIAG", resumo)
        RequestLog.add("DIAG", leitura)
        RequestLog.setOwnerProbeResumo((linhas + resumo + leitura).joinToString("\n"))
    }

    /**
     * Caminho legado/diagnóstico: inicia **apenas** o servidor HTTPS na thread chamadora.
     * Não use para o fluxo normal — o caminho correto é [startRouter].
     */
    fun startHttps(context: Context): Boolean {
        val current = server
        if (current?.isRunning == true) {
            publishStatus(httpsRunning = true)
            return true
        }
        val material = try {
            TlsContextFactory.FromBytes(
                readAsset(context, CdnRouterConfig.CERT_ASSET),
                CdnRouterConfig.CERT_PASSWORD.toCharArray()
            )
        } catch (e: Exception) {
            val message = "asset ${CdnRouterConfig.CERT_ASSET} indisponível: ${e.javaClass.simpleName}: ${e.message}"
            RequestLog.add("CDNI", "FALHA: $message")
            publishStatus(serverError = message)
            return false
        }
        val appContext = context.applicationContext
        val created = LocalHttpsServer(
            tlsMaterial = material,
            ownerDescription = { socket -> AndroidDiagnostics.connectionOwner(appContext, socket) }
        )
        val started = created.start()
        server = if (started) created else null
        val tunnelBound = created.boundEndpoints.any { it.role == LocalHttpsServer.EndpointRole.TUNEL_PRIMARIO }
        publishStatus(
            httpsRunning = started,
            endpoints = created.boundEndpoints.joinToString(", ") { it.toString() },
            serverError = if (started) null else "nenhum listener HTTPS ativo (ver log)",
            tunnelBound = tunnelBound
        )
        return started
    }

    fun stopHttps() {
        server?.stop()
        server = null
        publishStatus(httpsRunning = false, endpoints = "", tunnelBound = false)
    }

    /** Consenso do sistema (VpnService.prepare) ou null quando já autorizado. */
    fun vpnConsentIntent(context: Context): Intent? = VpnService.prepare(context)

    fun startVpn(context: Context, wzmPackage: String) {
        CdnVpnService.start(context, wzmPackage)
    }

    fun stopVpn(context: Context) {
        CdnVpnService.stop(context)
    }

    fun stopAll(context: Context) {
        RequestLog.add("LAUNCHER", "parando roteador CDNI local (VPN + listeners)")
        stopVpn(context)
        stopHttps()
        lifecycle.onStopped("parada solicitada pelo launcher")
        _status.value = CdnRouterStatus()
    }

    fun onVpnStateChanged(active: Boolean, error: String? = null) {
        if (!active && error != null) {
            lifecycle.onError(error)
        }
        publishStatus(vpnActive = active, vpnError = error)
    }

    private fun publishStatus(
        httpsRunning: Boolean = _status.value.httpsRunning,
        endpoints: String = _status.value.endpoints,
        serverError: String? = _status.value.serverError,
        vpnActive: Boolean = _status.value.vpnActive,
        vpnError: String? = _status.value.vpnError,
        tunnelBound: Boolean = _status.value.tunnelBound
    ) {
        _status.value = CdnRouterStatus(
            httpsRunning = httpsRunning,
            endpoints = endpoints,
            serverError = serverError,
            vpnActive = vpnActive,
            vpnError = vpnError,
            phase = lifecycle.phase,
            tunnelBound = tunnelBound,
            tunnelAddressAssigned = lifecycle.tunnelAddressAssigned,
            tunnelListenerMissingReason = lifecycle.tunnelListenerMissingReason,
            phaseHistory = lifecycle.history()
        )
    }

    /**
     * Copia a CA local (em claro) para o armazenamento do app e devolve o caminho.
     * Instalação é manual e documentada; nada é instalado silenciosamente.
     */
    fun exportCaCertificate(context: Context): String? {
        val target = File(context.getExternalFilesDir(null) ?: context.filesDir, CdnRouterConfig.CA_EXPORT_NAME)
        return try {
            context.assets.open(CdnRouterConfig.CA_ASSET).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            RequestLog.add("CA", "CA local copiada para ${target.absolutePath} (instalação é manual, ver docs)")
            target.absolutePath
        } catch (e: Exception) {
            RequestLog.add("CA", "falha ao copiar a CA local: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun readAsset(context: Context, name: String): ByteArray =
        context.assets.open(name).use { it.readBytes() }
}
