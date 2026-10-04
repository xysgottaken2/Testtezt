package com.wzm.launcher.cdn

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Descoberta de processos (M3.6) — pedido explícito: "diferencie app em primeiro plano, processo de
 * jogo e processos auxiliares".
 *
 * O que é **possível** observar sem root, sem permissão especial e sem tocar no APK do jogo:
 *
 *  * **processos do próprio launcher** — `ActivityManager.getRunningAppProcesses()` devolve os
 *    processos do app que chama (é o que a API garante desde a API 26);
 *  * **processos declarados no manifesto do WZM** — `PackageManager` com `GET_ACTIVITIES|GET_SERVICES|
 *    GET_RECEIVERS|GET_PROVIDERS` expõe `processName`; é **fato estático** ("existe esse processo"),
 *    não prova de execução;
 *  * **processos em execução de outro pacote** — a partir da API 26 a consulta devolve vazio para
 *    outro UID (e desde a API 30 a visibilidade de pacotes é filtrada). Quando isso acontece o log diz
 *    `INDISPONIVEL` com o motivo — **nunca** se conclui "o jogo não está rodando" a partir de uma
 *    lista vazia.
 *
 * Nada aqui altera estado: só leitura. Os testes cobrem [describe], que é pura.
 */
object ProcessDiscovery {

    /** Fatos coletados (todos observáveis ou explicitamente indisponíveis). */
    data class ProcessFacts(
        val targetPackage: String,
        val targetUid: Int?,
        val ownUid: Int,
        val ownProcesses: List<String>,
        val ownProcessesAvailable: Boolean,
        val ownProcessesNote: String,
        val targetRunningProcesses: List<String>,
        val targetRunningNote: String,
        val targetDeclaredProcesses: List<String>,
        val declaredAvailable: Boolean
    )

    /**
     * Linhas de log do bloco de processos. Puras: recebem os fatos prontos.
     * Nenhuma linha afirma "o jogo está/não está rodando" sem que a API tenha respondido.
     */
    fun describe(facts: ProcessFacts): List<String> {
        val lines = mutableListOf<String>()
        lines += if (facts.ownProcessesAvailable) {
            "processos do launcher (uid=${facts.ownUid}): " + facts.ownProcesses.joinToString(", ")
        } else {
            "processos do launcher (uid=${facts.ownUid}): INDISPONIVEL (${facts.ownProcessesNote})"
        }
        val declared = when {
            !facts.declaredAvailable ->
                "INDISPONIVEL (PackageManager não devolveu manifesto do alvo)"
            facts.targetDeclaredProcesses.isEmpty() ->
                "nenhum processo separado declarado (tudo roda no processo principal)"
            else ->
                facts.targetDeclaredProcesses.joinToString(", ") + " — declarados no manifesto (não prova execução)"
        }
        lines += "processos declarados do ${facts.targetPackage}: $declared"
        val running = when {
            facts.targetRunningProcesses.isNotEmpty() ->
                facts.targetRunningProcesses.joinToString(", ") + " — em execução agora (VERIFIED)"
            else ->
                "INDISPONIVEL (${facts.targetRunningNote}) — lista vazia NÃO prova que o app não roda"
        }
        lines += "processos em execução do ${facts.targetPackage}: $running"
        if (facts.targetUid != null) {
            lines += "atenção: contadores de rede são por UID (${facts.targetUid}) — " +
                "processos auxiliares do MESMO pacote entram na mesma conta"
        }
        return lines
    }

    /** Coleta real (leitura apenas). Nunca lança. */
    fun collect(context: Context, targetPackage: String, targetUid: Int?): ProcessFacts {
        var ownNote = "getRunningAppProcesses devolveu null"
        val own = mutableListOf<String>()
        val ownAvailable = runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
            val processes = manager?.runningAppProcesses
            if (processes == null) {
                ownNote = "getRunningAppProcesses devolveu null (API restrita neste device)"
                false
            } else {
                processes.filter { it.uid == android.os.Process.myUid() }.forEach { info ->
                    own += "pid=${info.pid} nome=${info.processName} importance=${info.importance}"
                }
                true
            }
        }.getOrElse { error ->
            ownNote = "${error.javaClass.simpleName}: ${error.message}"
            false
        }

        var targetNote = "não consultado"
        val targetRunning = mutableListOf<String>()
        if (targetUid == null) {
            targetNote = "sem UID do alvo (pacote não instalado/visível)"
        } else {
            runCatching {
                val manager = context.getSystemService(ActivityManager::class.java)
                val processes = manager?.runningAppProcesses ?: emptyList()
                processes.filter { it.uid == targetUid }.forEach { info ->
                    targetRunning += "pid=${info.pid} nome=${info.processName} importance=${info.importance}"
                }
                targetNote = "getRunningAppProcesses não devolveu processos do uid $targetUid " +
                    "(desde a API 26 a API só devolve processos do PRÓPRIO app; API 30+ ainda filtra " +
                    "visibilidade de pacotes) — não é possível afirmar execução por aqui"
            }.onFailure { error ->
                targetNote = "${error.javaClass.simpleName}: ${error.message}"
            }
        }

        val declared = mutableListOf<String>()
        var declaredAvailable = false
        runCatching {
            val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
            val info = context.packageManager.getPackageInfo(targetPackage, flags)
            declaredAvailable = true
            val applicationProcess = info.applicationInfo?.processName
            if (!applicationProcess.isNullOrBlank()) declared += applicationProcess
            info.activities?.forEach { activity -> activity.processName?.takeIf { it.isNotBlank() }?.let { declared += it } }
            info.services?.forEach { service -> service.processName?.takeIf { it.isNotBlank() }?.let { declared += it } }
            info.receivers?.forEach { receiver -> receiver.processName?.takeIf { it.isNotBlank() }?.let { declared += it } }
            info.providers?.forEach { provider -> provider.processName?.takeIf { it.isNotBlank() }?.let { declared += it } }
        }

        return ProcessFacts(
            targetPackage = targetPackage,
            targetUid = targetUid,
            ownUid = android.os.Process.myUid(),
            ownProcesses = own,
            ownProcessesAvailable = ownAvailable,
            ownProcessesNote = ownNote,
            targetRunningProcesses = targetRunning,
            targetRunningNote = targetNote,
            targetDeclaredProcesses = declared.distinct().sorted(),
            declaredAvailable = declaredAvailable
        )
    }
}
