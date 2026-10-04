#!/usr/bin/env bash
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo 'Run as root'; exit 1; }
if command -v caddy >/dev/null; then
  echo 'Caddy already exists; review existing configuration instead of overwriting.'; exit 1
fi
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl gpg
curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt -o /etc/apt/sources.list.d/caddy-stable.list
chmod 644 /usr/share/keyrings/caddy-stable-archive-keyring.gpg /etc/apt/sources.list.d/caddy-stable.list
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y caddy
cat > /etc/caddy/Caddyfile <<'EOF'
{
    admin localhost:2019
}
api.pebble-log.com {
    @api path /api/v1/*
    handle @api {
        reverse_proxy 127.0.0.1:8080
    }
    handle {
        respond 404
    }
}
EOF
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
# Keep SSH and the existing firewall rules; add only public web ports.
for port in 80 443; do
    if ! iptables -C INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null; then
        iptables -I INPUT 1 -p tcp --dport "$port" -j ACCEPT
    fi
done
if [ -f /etc/iptables/rules.v4 ]; then
    cp -n /etc/iptables/rules.v4 /etc/iptables/rules.v4.before-pebble-https
    iptables-save > /etc/iptables/rules.v4
fi
systemctl enable caddy
systemctl reload caddy
systemctl is-active caddy
