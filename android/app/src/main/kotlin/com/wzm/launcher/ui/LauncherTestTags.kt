package com.wzm.launcher.ui

/**
 * Tags de teste da UI (M3.5.1) — usadas pelo teste de rolagem em JVM/Robolectric
 * (`LauncherScreenScrollTest`) e disponíveis para qualquer teste instrumentado.
 *
 * Elas existem porque o bug de 2026-10-04 no device era de **layout**: depois do teste sintético o
 * relatório aumentava a altura do conteúdo e, sem rolagem, os botões seguintes ficavam inalcançáveis.
 * Um teste de UI precisa apontar para o container rolável e para os botões, não para textos soltos.
 */
object LauncherTestTags {
    /** Container rolável da tela principal (o `LazyColumn` que envolve todo o conteúdo). */
    const val CONTENT = "launcher_content"

    /** Botão "INICIAR WARZONE MOBILE" — precisa continuar alcançável depois do teste sintético. */
    const val LAUNCH_WZM = "launcher_launch_wzm"

    /** Botão "TESTE SINTÉTICO …". */
    const val SYNTHETIC_BUTTON = "launcher_synthetic_button"

    /** Card com o relatório do teste sintético (altura variável — é ele que empurrava o resto). */
    const val SYNTHETIC_REPORT = "launcher_synthetic_report"

    /** Card com o resultado do teste de controle de autoria do M4.1 (altura variável). */
    const val OWNER_PROBE = "launcher_owner_probe"

    /** Painel de preview do log (altura fixa; rola por dentro, sem bloquear a tela). */
    const val LOG_PANEL = "launcher_log_panel"

    /** Botão RECOLHER/MOSTRAR do painel de log. */
    const val LOG_TOGGLE = "launcher_log_toggle"

    /** Linha com VER LOGS + EXPORTAR CA LOCAL (ações depois do log). */
    const val ACTION_ROW = "launcher_action_row"
}
