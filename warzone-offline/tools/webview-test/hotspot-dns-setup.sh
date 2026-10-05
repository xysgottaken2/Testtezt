#!/usr/bin/env bash
# Retired M2.1 hotspot harness.
#
# This historical test would expose local services on a Wi-Fi interface and alter DNS/NAT rules.
# Current scope forbids non-loopback listeners, root/iptables changes, and TLS interception until
# the WZM network path is established. The script intentionally performs no setup or cleanup.
set -euo pipefail

case "${1:-}" in
  --help|--dry-run)
    cat <<'HELP'
This M2.1 hotspot/DNS/NAT experiment is retired and disabled.
No server, hotspot, dnsmasq, iptables rule, proxy, or TLS/CA change is started.
The local BootstrapServer is limited to 127.0.0.1 and is not reachable from another device.
See docs/research/m3.2-apk-tls-trust-investigation.md and
 docs/research/m4.1-dono-das-conexoes-loopback.md.
HELP
    ;;
  --start)
    echo "blocked: hotspot/DNS/NAT and remote server binding are out of scope; no changes made" >&2
    exit 2
    ;;
  --stop)
    echo "no-op: this retired script made no changes; inspect/restore any legacy host rules manually" >&2
    ;;
  *)
    echo "usage: $0 --help|--dry-run|--start|--stop (start is intentionally blocked)" >&2
    exit 2
    ;;
esac
