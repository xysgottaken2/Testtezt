package com.wzm.launcher.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import com.wzm.launcher.cdn.RequestCounters
import com.wzm.launcher.server.ServerStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Hotfix M3.5.1 (bug de UI visto no device em 2026-10-04):
 *
 * depois do **TESTE SINTÉTICO** o relatório aumentava a altura do conteúdo e a tela — que era um
 * `Column` **sem rolagem**, com o painel de log em `weight(1f)` — não rolava mais: o botão
 * **INICIAR WARZONE MOBILE** e os controles seguintes ficavam fora da tela, inalcançáveis.
 *
 * Estes testes rodam na JVM (Robolectric, sem device) dentro do `testDebugUnitTest` do CI e fixam o
 * contrato do layout: o conteúdo é rolável, o painel de log tem altura limitada e o botão continua
 * alcançável em qualquer combinação de relatório longo + log cheio.
 *
 * Nada de rede/VPN/CDNI é exercitado aqui: o composable testado ([LauncherContent]) recebe o estado
 * pronto e handlers vazios.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class LauncherScreenScrollTest {

    @get:Rule
    val rule = createComposeRule()

    /** Relatório no formato do [com.wzm.launcher.cdn.SyntheticFlowTest] — longo, como no device. */
    private fun longSyntheticReport(): String = buildString {
        append("TESTE SINTÉTICO DNS → 10.111.222.1:443 → cdni.meta: VERIFIED\n")
        for (passo in 1..6) {
            append("passo$passo: ok — ")
            append("detalhe do passo $passo com texto suficiente para ocupar mais de uma linha na tela\n")
        }
    }

    private fun fullLog(count: Int): List<String> = List(count) { "[TUN] pacote $it classificado" }

    private fun state(
        report: String? = null,
        logs: List<String> = fullLog(300)
    ) = LauncherUiState(
        serverStatus = ServerStatus.ONLINE,
        counters = RequestCounters(),
        logs = logs,
        syntheticReport = report
    )

    @Test
    fun launchWzmStaysReachableAfterSyntheticTestWithLongReport() {
        rule.setContent { LauncherContent(state = state(report = longSyntheticReport())) }

        // O conteúdo inteiro vive numa lista rolável (a correção do hotfix).
        rule.onNodeWithTag(LauncherTestTags.CONTENT).assertExists()

        // E o botão do WZM continua alcançável — era exatamente isso que quebrava.
        rule.onNodeWithTag(LauncherTestTags.CONTENT)
            .performScrollToNode(hasTestTag(LauncherTestTags.LAUNCH_WZM))
        rule.onNodeWithTag(LauncherTestTags.LAUNCH_WZM).assertIsDisplayed()
    }

    @Test
    fun syntheticReportIsRenderedAndDoesNotHideTheButtonsBelow() {
        rule.setContent { LauncherContent(state = state(report = longSyntheticReport())) }

        rule.onNodeWithTag(LauncherTestTags.CONTENT)
            .performScrollToNode(hasTestTag(LauncherTestTags.SYNTHETIC_REPORT))
        rule.onNodeWithTag(LauncherTestTags.SYNTHETIC_REPORT).assertIsDisplayed()

        // Rolando um pouco mais, a linha de ações (VER LOGS / EXPORTAR CA) também aparece.
        rule.onNodeWithTag(LauncherTestTags.CONTENT)
            .performScrollToNode(hasTestTag(LauncherTestTags.ACTION_ROW))
        rule.onNodeWithTag(LauncherTestTags.ACTION_ROW).assertIsDisplayed()
    }

    @Test
    fun logPanelHasBoundedHeightAndDoesNotConsumeTheScreen() {
        rule.setContent { LauncherContent(state = state(report = null, logs = fullLog(500))) }

        // Altura fixa: mesmo com 500 linhas o painel não cresce nem empurra o resto para fora.
        // Comparação em pixels (sem depender de API de dp do compose-test): o painel precisa ocupar
        // menos da METADE da área rolável — antes ele era weight(1f) num Column sem rolagem.
        rule.onNodeWithTag(LauncherTestTags.LOG_PANEL).assertIsDisplayed()
        val panelPx = rule.onNodeWithTag(LauncherTestTags.LOG_PANEL).fetchSemanticsNode().size.height
        val contentPx = rule.onNodeWithTag(LauncherTestTags.CONTENT).fetchSemanticsNode().size.height
        assertTrue(
            "painel de log não pode consumir a tela: ${panelPx}px de ${contentPx}px",
            panelPx * 2 <= contentPx
        )

        // O botão do WZM vem ANTES do painel e fica visível sem nenhuma rolagem.
        rule.onNodeWithTag(LauncherTestTags.LAUNCH_WZM).assertIsDisplayed()
    }

    @Test
    fun logPanelCanBeCollapsedAndExpandedAgain() {
        rule.setContent { LauncherContent(state = state(report = null)) }

        rule.onNodeWithTag(LauncherTestTags.LOG_PANEL).assertIsDisplayed()
        rule.onNodeWithTag(LauncherTestTags.LOG_TOGGLE).performClick()
        rule.onNodeWithTag(LauncherTestTags.LOG_PANEL).assertDoesNotExist()
        rule.onNodeWithTag(LauncherTestTags.LOG_TOGGLE).performClick()
        rule.onNodeWithTag(LauncherTestTags.LOG_PANEL).assertIsDisplayed()
    }

    @Test
    fun screensWithoutReportAlsoScrollToTheLastItem() {
        // Sem relatório, com log vazio: a lista continua rolável até o rodapé (nada quebra com estado inicial).
        rule.setContent { LauncherContent(state = LauncherUiState()) }

        rule.onNodeWithTag(LauncherTestTags.CONTENT).assertExists()
        rule.onNodeWithTag(LauncherTestTags.SYNTHETIC_REPORT).assertDoesNotExist()
        rule.onNodeWithTag(LauncherTestTags.LAUNCH_WZM).assertIsDisplayed()
    }
}
