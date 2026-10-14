#!/usr/bin/env bash
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo 'Run as root'; exit 1; }
list="${1:?Pass the verified Cloudflare IPv4 list}"
python3 - "$list" <<'PY'
import ipaddress,sys
lines=open(sys.argv[1]).read().splitlines()
assert 10 <= len(lines) <= 100
for line in lines:
 n=ipaddress.ip_network(line,strict=True)
 assert n.version==4 and n.prefixlen>=8 and n.network_address.is_global
PY
if iptables -S PEBBLE_CF_WEB >/dev/null 2>&1; then
 echo 'Origin restriction already exists; review it before updating.'; exit 1
fi
backup=/etc/iptables/rules.v4.before-cloudflare-origin
[ ! -e "$backup" ] || { echo 'Backup already exists; refusing overwrite'; exit 1; }
iptables-save > "$backup"
chmod 600 "$backup"
trap 'iptables-restore < "$backup"' ERR
iptables -N PEBBLE_CF_WEB
iptables -A PEBBLE_CF_WEB -i lo -j ACCEPT
while IFS= read -r cidr; do
 iptables -A PEBBLE_CF_WEB -s "$cidr" -j ACCEPT
done < "$list"
iptables -A PEBBLE_CF_WEB -p tcp -j REJECT --reject-with tcp-reset
iptables -I INPUT 1 -p tcp -m multiport --dports 80,443 -j PEBBLE_CF_WEB
for port in 80 443; do
 while iptables -C INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null; do
  iptables -D INPUT -p tcp --dport "$port" -j ACCEPT
 done
done
# Test through the real Cloudflare hostname before persisting; rollback on failure.
curl -fsS --connect-timeout 5 --max-time 15 https://api.pebble-log.com/api/v1/posts -o /dev/null
iptables-save > /etc/iptables/rules.v4
trap - ERR
echo 'Cloudflare-only TCP 80/443 restriction active; SSH rules unchanged.'
