#!/usr/bin/env bash
# hotspot-dns-setup.sh — setup menos invasivo para S23 Ultra testar WebView contra BootstrapServer
# Uso:
#   bash hotspot-dns-setup.sh --dry-run          # mostra comandos sem executar (ideal para review S23)
#   sudo bash hotspot-dns-setup.sh --start       # cria hotspot + dnsmasq + iptables + bootstrap (PC)
#   sudo bash hotspot-dns-setup.sh --stop        # reverte tudo
# Sem root no S23, sem tocar Activision, sem auth/Demonware.
set -euo pipefail

PC_IP="10.42.0.1"
HOTSPOT_SSID="WZM-Offline-Test"
HOTSPOT_PASS="wzmoffline123"
HOTSPOT_CON="WZM-Offline-Test"
DNS_HOST="prod.cdni.callofduty.com"
BOOTSTRAP_PORT="18081"

DRY_RUN="false"
ACTION="dry-run"
for arg in "$@"; do case $arg in --dry-run) DRY_RUN=true; ACTION="dry-run" ;; --start) ACTION="start" ;; --stop) ACTION="stop" ;; --help) cat <<HELP
Usage: bash hotspot-dns-setup.sh [--dry-run|--start|--stop]
  --dry-run : mostra comandos (não executa) — menos invasivo, para review no S23
  --start   : cria hotspot NetworkManager + dnsmasq + iptables + bootstrap
  --stop    : reverte hotspot/dnsmasq/iptables

Requisitos PC: NetworkManager, dnsmasq, iptables, node (para BootstrapServer)
S23: sem root, só conectar no Wi-Fi WZM-Offline-Test (ver m2.1-webview-test-plan.md §3.2)
HELP
exit 0 ;; esac; done

run() { if [[ "$DRY_RUN" == "true" ]]; then echo "[dry-run] $*"; else echo "[run] $*"; eval "$*"; fi; }

if [[ "$ACTION" == "dry-run" ]]; then
  echo "=== M2.1 Hotspot DNS — dry-run (nenhuma mudança no PC/S23) ==="
  echo "# PC hotspot (NetworkManager):"
  run "nmcli device wifi hotspot ifname wlan0 con-name $HOTSPOT_CON ssid $HOTSPOT_SSID band bg password $HOTSPOT_PASS"
  echo "# DNS override:"
  run "sudo dnsmasq --no-daemon --listen-address=$PC_IP --address=/$DNS_HOST/$PC_IP --port=53 &"
  echo "# NAT 443 -> $BOOTSTRAP_PORT:"
  run "sudo iptables -t nat -A PREROUTING -p tcp --dport 443 -j REDIRECT --to-port $BOOTSTRAP_PORT"
  echo "# BootstrapServer:"
  run "cd /home/user/Testtezt/warzone-offline/server && npm run dev:bootstrap -- --host 0.0.0.0 --port $BOOTSTRAP_PORT"
  echo "# Verificar no PC: curl http://$PC_IP:$BOOTSTRAP_PORT/__hits"
  echo "# No S23: Wi-Fi WZM-Offline-Test + adb logcat | grep -i cdni"
  echo "=== fim dry-run ==="
  exit 0
fi

if [[ "$ACTION" == "stop" ]]; then
  echo "=== M2.1 stop — revertendo ==="
  run "sudo iptables -t nat -D PREROUTING -p tcp --dport 443 -j REDIRECT --to-port $BOOTSTRAP_PORT 2>/dev/null || true"
  run "sudo pkill -f \"dnsmasq.*$DNS_HOST\" || true"
  run "nmcli connection down $HOTSPOT_CON || true"
  run "nmcli connection delete $HOTSPOT_CON || true"
  echo "Revertido. No S23: esquecer Wi-Fi WZM-Offline-Test"
  exit 0
fi

if [[ "$ACTION" == "start" ]]; then
  if [[ "$EUID" -ne 0 ]]; then echo "Para --start, rode com sudo: sudo bash hotspot-dns-setup.sh --start"; exit 1; fi
  echo "=== M2.1 start ==="
  nmcli device wifi hotspot ifname wlan0 con-name "$HOTSPOT_CON" ssid "$HOTSPOT_SSID" band bg password "$HOTSPOT_PASS" || true
  PC_IP_ACTUAL=$(ip -4 addr show | grep -oP '10\.42\.0\.\d+' | head -n1 || echo "$PC_IP")
  echo "PC IP: $PC_IP_ACTUAL"
  dnsmasq --listen-address="$PC_IP_ACTUAL" --address="/$DNS_HOST/$PC_IP_ACTUAL" --port=53 &
  echo "dnsmasq pid $! for $DNS_HOST -> $PC_IP_ACTUAL"
  iptables -t nat -A PREROUTING -p tcp --dport 443 -j REDIRECT --to-port "$BOOTSTRAP_PORT"
  echo "iptables 443 -> $BOOTSTRAP_PORT"
  echo "Inicie BootstrapServer: cd warzone-offline/server && npm run dev:bootstrap -- --host 0.0.0.0 --port $BOOTSTRAP_PORT"
  echo "S23: conectar em $HOTSPOT_SSID / $HOTSPOT_PASS, abrir WZM, verificar PC: curl http://$PC_IP_ACTUAL:$BOOTSTRAP_PORT/__hits"
  exit 0
fi
