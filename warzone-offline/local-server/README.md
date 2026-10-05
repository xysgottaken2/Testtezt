# Local Server (M5.0) — DNS + servidor local para o WZM 3.10.0

Servidor mínimo, em Python puro, que reproduz as respostas que o WZM espera **mantendo o hostname original**.

```
WZM ──► https://prod.cdni.callofduty.com/… ──► DNS local ──► IP do servidor ──► este processo
                                                                                 ├─ cdni.meta (corpo real)
                                                                                 ├─ 404 registrado (= próxima etapa)
                                                                                 └─ log por estágio (JSONL)
```

**Não é proxy, não depende de VPN, não altera o cliente.** A VPN do launcher, se usada, é apenas um *transporte* para entregar o DNS. O contrato é: alguém resolve o hostname para o nosso IP, e nós respondemos.

## Arquivos

| Arquivo | Papel |
|---|---|
| `wzm_local_server.py` | HTTPS (443) + HTTP (8080). Rotas mínimas + log por estágio (SNI, handshake, request) |
| `wzm_dns.py` | DNS autoritativo para os hosts do jogo (A → IP do servidor), encaminha o resto |
| `routes.json` | tabela de rotas — só entra o que foi verificado |
| `data/cdni.meta.json` | corpo **real** capturado do `cdni.meta` (nenhum byte inventado) |
| `certs/make-local-cert.sh` | gera CA + certificado do servidor (lado servidor; não mexe no cliente) |
| `logs/*.jsonl` | logs (não versionados) — DNS e HTTP, nunca corpos/credenciais |

## 0. Preparar (uma vez)

```bash
cd warzone-offline/local-server
bash certs/make-local-cert.sh                 # gera certs/ca.pem, server.pem, server.key
python3 wzm_local_server.py --self-test       # rotas + TLS + log
python3 wzm_dns.py --self-test                # A/curinga/AAAA/REFUSED/TCP
```

## 1. Subir

```bash
# DNS (porta 53 exige root/cap no Linux)
sudo python3 wzm_dns.py --bind 0.0.0.0 --port 53 \
     --host prod.cdni.callofduty.com=192.168.0.10 \
     --wildcard cdni.callofduty.com=192.168.0.10 \
     --upstream 8.8.8.8 --log logs/dns.jsonl

# Servidor (porta 443 exige root/cap no Linux)
sudo python3 wzm_local_server.py --bind 192.168.0.10 --https-port 443 --http-port 8080 \
     --cert certs/server.pem --key certs/server.key --log logs/requests.jsonl
```

* Substitua `192.168.0.10` pelo IP da máquina na mesma rede do celular.
* `--bind` padrão é `127.0.0.1` (ninguém alcança); expor exige passar o IP explicitamente.
* Sem root: `sudo setcap 'cap_net_bind_service=+ep' $(which python3)` ou use `--https-port 8443` + redirecionamento. A app pede a 443, então o caminho limpo é 443.
* Firewall: liberar entrada UDP/53 e TCP/443 (e TCP/8080 se quiser testar em claro).

## 2. Apontar o DNS do aparelho (sem root)

1. **Private DNS → Desligado** (obrigatório: em `Automático`/`hostname` o sistema tenta DoT na 853 e nunca nos consulta).
2. Wi-Fi → toque longo na rede → **Modificar** → **Configurações de IP: Estático** → **DNS 1 = 192.168.0.10**, **DNS 2 vazio**.
3. Confirme em `logs/dns.jsonl`: a consulta do UID/nome do jogo aparece como `"watched": true`.

Alternativas: `/etc/hosts` no aparelho (root) ou a VPN do launcher como transporte de DNS. Nenhuma delas é requisito deste servidor.

## 3. Ler o log — os 4 estágios

`logs/requests.jsonl` registra cada estágio separadamente (é assim que a gente não mistura DNS, TLS, protocolo e conteúdo):

| Estágio | Evento no log | O que prova |
|---|---|---|
| **1. DNS** | `logs/dns.jsonl` → `"result": "autoritativo_a"` | o nome resolveu para nós |
| **2. TCP + ClientHello** | `"event": "tls_client_hello"`, `"sni": "prod.cdni.callofduty.com"` | o cliente conectou em nós e pediu o host certo |
| **3. Handshake TLS** | `"event": "tls_handshake_failed"` | **blocker esperado**: o cliente não confia no nosso certificado |
| **4. Request** | `"event": "http_request"`, `"path": "/wzm/…/cdni.meta"`, `"status": 200` | o protocolo rodou; a partir daqui o 404 registrado revela a próxima request |

O estágio 3 é o esperado **sem nenhuma mudança de confiança**: o cliente exige um certificado válido para `prod.cdni.callofduty.com`. Quando ele aparecer, ele fica documentado como o blocker do momento — a decisão sobre confiança (CA de usuário/sistema) é separada e não foi tomada aqui.

## 4. Regra de trabalho deste milestone

```
descobrir  →  implementar UMA rota  →  testar no WZM  →  capturar a próxima request  →  repetir
```

1. Rota só entra em `routes.json` depois de o cliente pedi-la de verdade.
2. Resposta desconhecida = **404 registrado** no log, nunca 200 inventado.
3. Nada de lote de endpoints hipotéticos.

## Limites

* Não altera TLS/pinning/trust **do cliente**, autenticação, anti-cheat, o APK ou o `libgame.so`.
* Não registra corpos de requisição/resposta; valores de `Authorization`, `Cookie`, `Set-Cookie`, `X-Auth-*`, `X-Api-Key` vão como `<redacted>`.
* Não versiona `.pem`, `.key`, `.apk`, `.dex` nem qualquer binário do jogo.
