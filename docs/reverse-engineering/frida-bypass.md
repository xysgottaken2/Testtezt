# Frida / certificate pinning — procedimento retirado

> **DEPRECATED — NÃO EXECUTAR.** Este arquivo permanece apenas como registro do escopo histórico M0–M2.
> Os passos que antes estavam aqui foram retirados porque conflitam com a restrição vigente de investigação
> read-only. Não usar Frida, root, proxy/CA de interceptação, bypass de pinning/trust, patch ou repack do APK.

M3.2/M4.1 substituem integralmente este procedimento: não tocar em TLS/trust/pinning até atribuir o caminho
real de rede do WZM. `INVALID_UID`, loopback, pacotes TUN sem UID e ausência de evento continuam
inconclusivos; consultar [M3.2 — investigação read-only](../research/m3.2-apk-tls-trust-investigation.md)
e [M4.1 — dono das conexões](../research/m4.1-dono-das-conexoes-loopback.md).

Não há instrução de bypass neste documento. Nenhuma ação deste tipo foi executada.
