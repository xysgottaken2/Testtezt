# Warzone Mobile — Asset Streaming & CDN

> **Data:** 2026-10-04 | **Status:** M0 Research

---

## 1. Por que streaming importa

Warzone Mobile foi criticado exatamente por isso: usar **IW 9.0 (PC engine) com texture streaming** em celular gera aquecimento e queda de FPS (NamuWiki). O jogo não cabe inteiro no APK — parte é baixada sob demanda via CDN, o que afeta diretamente o servidor offline: se o CDN estiver fora, o cliente pode se recusar a iniciar ou ficar com texturas pretas / mapa incompleto.

Entender o streaming é pré-requisito para M12.

---

## 2. O que é sabido (PROBABLE → NEEDS_RESEARCH para detalhes)

| Observação | Fonte | Confiança |
|---|---|---|
| APK base ~19–320 MB (XAPK), OBB/streaming 3.5–5.5 GB adicionais baixados ao abrir o jogo | MobileMatters, GINX | PROBABLE |
| Pasta `split_asset_pack/assets/shard/` + `manifest.json` dentro do APK/XAPK | ResHax thread 2024-04-21 | PROBABLE |
| Arquivos `.shard` com assinatura `IWffn100`, alguns parecem `KAPIs (xpak)` com estrutura clara | ResHax | PROBABLE |
| `manifest.json` provavelmente lista shards, hashes, dependências | ResHax (usuário pergunta como abrir, outro diz que manifest não está criptografado) | HYPOTHESIS |
| Usuário relata tentar reverter algoritmo de criptografia do manifest — indica que pelo menos parte é criptografada/assinada | ResHax | HYPOTHESIS |
| Streaming visto em lobby: “streaming the graphics from another server … they need to hurry up and let us download them” | r/WarzoneMobile 2024-04-26 | VERIFIED (comportamento percebido pelo jogador) |
| IW 8.0+ tem texture streaming para baixar texturas HD durante a partida (PC) | COD Wiki IW 8.0 revamped engine | VERIFIED (PC) → HYPOTHESIS (mobile usa igual) |

---

## 3. Arquitetura hipotética de streaming

```
Play Store (XAPK ~320 MB)
  └─ base APK + split_asset_pack (shard stub + manifest.json)
       │
       └─ ao iniciar: cliente lê manifest.json
            → verifica cache local (obb / shard/)
            → baixa shards faltantes do CDN (HTTPS, chunks)
            → verifica hash (sha256/md5?)
            → monta mapa / texturas

CDN (possível):
  cdn.*.activision.com / cdn.*.demonware.net / *.googleapis.com
  manifest.json + *.shard + *.xpak
```

**Tamanho por versão:**

- S2: ~3.5 GB
- S5: ~5.5 GB adicional

→ Jogo completo instalado pode passar de 6–8 GB.

---

## 4. Classificação proposta (usar em docs/maps/*)

Para cada asset, classificar:

| Categoria | Descrição | Exemplo |
|---|---|---|
| **Client-local** | Já vem no XAPK/OBB base, sem CDN | APK base, executável, libs `.so` |
| **Downloaded** | Baixado na primeira inicialização e cacheado | `shard/` iniciais |
| **CDN-dependent** | Só existe no CDN, cliente tenta baixar | Shards de POIs S5, texturas HD |
| **Server-dependent** | Servidor precisa fornecer (não CDN) | Session, player state |
| **Can be emulated locally** | Podemos servir de `localhost` via hosts override | `manifest.json` + shards se cliente aceitar localhost |
| **Unknown** | Ainda não sabemos | — |

---

## 5. Perguntas em aberto (UNKNOWN)

1. **manifest.json é criptografado/assinado?** Um usuário diz que não, outro tenta reverter criptografia — pode variar por versão. Precisa inspecionar um manifest real.
2. **Shards são criptografados?** Assinatura `IWffn100` sugere header IW, mas conteúdo pode ser comprimido/criptografado.
3. **Dependências entre shards:** manifest lista ordem/chunks? Se faltar um shard, o cliente trava ou cai para LOD baixo?
4. **Cache:** onde fica? `Android/data/com.activision.callofduty.warzone/`? `Android/obb/`? `cache/`?
5. **Obrigatório vs opcional:** quais shards são obrigatórios para entrar em Verdansk vanilla vs opcionais (texturas HD, skins)?
6. **CDN URL:** qual domínio? Precisa de `strings` no APK.

---

## 6. Estratégia para servidor offline

### Fase A — Preservação (M0-M1)

- Extrair `manifest.json` + listar `*.shard` de um XAPK preservado
- Documentar contagem, tamanho, hash, header (`IWffn100`?)
- Testar: apagar cache e ver se cliente inicia offline (sem rede) — se iniciar, parte é client-local

### Fase B — Emulação local (M12)

- Servir `manifest.json` + shards de `127.0.0.1` via hosts override:

```
127.0.0.1   cdn.warzone-mobile.activision.com   # [HYPOTHESIS] domínio real a descobrir
```

- Servidor HTTP mock que loga `GET /manifest.json`, responde com manifest local e serve shards do disco
- Verificar se cliente aceita cert self-signed (precisa `VerifyPeer=false` ou Frida)

### Fase C — Minimização

- Identificar **mínimo de shards para Verdansk vanilla 120p** — evitar ter que emular 5 GB completos
- Se o cliente já tem Verdansk base no OBB, talvez só precise de manifest que diga “tudo ok”

---

## 7. Ferramentas

| Ferramenta | Uso |
|---|---|
| `apktool d` | Extrair XAPK, ver `split_asset_pack/` |
| `aapt dump` | Manifest do APK |
| `strings` / `hexdump -C` | Header `IWff` de shards |
| `jadx` | Procurar código que lê `manifest.json` |
| `mitmproxy` + Frida | Interceptar GETs de manifest/shard ao iniciar |
| `adb shell ls -R /sdcard/Android/data/...` | Localizar cache em device |

---

## 8. Próximos experimentos

1. **Shard inventory** — script `tools/asset-tools/shard-inventory.py` que lista shards + tamanho + header.
2. **Manifest parse** — tentar `jq . manifest.json` e documentar campos (se JSON válido).
3. **Offline boot test** — instalar APK preservado, negar internet via firewall, observar comportamento.
4. **CDN host discovery** — `grep -r "manifest\|shard\|cdn\|xpak" jadx-output`.

---

## 9. Fontes

**XAPK 320 MB + 3.5/5.5 GB:** MobileMatters (2024-05-28), GINX S5 (2024-07-19) — PROBABLE
**split_asset_pack/assets/shard/ + manifest.json + IWffn100 + KAPIs/xpak:** ResHax Warzone Mobile .shard (2024-04-21) — PROBABLE
**Streaming em lobby:** r/WarzoneMobile “what’s streaming mean” (2024-04-26) — VERIFIED (relato)
**Texture streaming IW 8.0:** COD Wiki / NamuWiki IW engine — VERIFIED (PC)
