# Launcher — estado Stable

> Esta página substitui o rascunho M0. A antiga lista `hosts override → Frida → adb` e os domínios `.example` eram placeholders/propostas, não uma implementação nem evidência de Warzone Mobile. Não usar root, Frida, hook, MITM/CA, bypass de pinning ou `VerifyPeer=false`.

## O que existe

- O app Android Stable detecta e inicia o pacote WZM via `PackageManager`.
- O roteador CDNI local usa `VpnService` com allow-list per-app e rotas explícitas; o `DnsResponder` só redireciona consultas que chegam ao DNS virtual do túnel.
- O servidor local responde paths documentados/sintéticos de diagnóstico. Resposta local não prova que o WZM fez aquela requisição.
- `src/hosts-patch/patch.ts` contém transformações puras de linhas de hosts e testes; não altera `/etc/hosts`, configura DNS do aparelho ou aponta o WZM a um servidor.
- A pasta `src/webview-patch/` está retirada/deprecated e não contém integração ativa com o WebView do WZM.

## Configuração direta do cliente

A busca somente leitura M4.2 não encontrou campo/base URL/override de endpoint WZM no material disponível. O binário WZM e `libgame.so` não estão no workspace; por isso não se pode declarar que essa opção inexiste no jogo. Ver [`docs/research/m4.2-configuracao-endpoint-local.md`](../../docs/research/m4.2-configuracao-endpoint-local.md).

## Limites vigentes

- Não inventar host, manifest ou endpoint.
- Não modificar o APK WZM nem alterar TLS/trust/pinning.
- Não tratar DbD como evidência WZM.
- O controle sintético e o controle loopback do launcher não são tráfego WZM.
- Próxima etapa, se a busca de configuração direta não mudar com novo artefato: `CONTROL_ONLY` com UID distinto e depois observação TUN/WZM separadas, sem inferir causa por ausência.
