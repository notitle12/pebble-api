#!/usr/bin/env bash
set -euo pipefail

# 확인한 Ubuntu 서버에 Docker 공식 저장소 패키지만 설치한다.
source /etc/os-release
if [[ "$ID" != ubuntu || "$VERSION_ID" != 24.04 ]]; then
  echo 'Expected Ubuntu 24.04; refusing unattended install.' >&2
  exit 1
fi
if command -v docker >/dev/null; then
  sudo -n docker version
  sudo -n docker compose version
  exit 0
fi
for package in docker.io podman-docker containerd runc; do
  if dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -q 'install ok installed'; then
    echo "Existing $package installation requires review; no packages removed." >&2
    exit 1
  fi
done
sudo -n apt-get update -qq
sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ca-certificates curl
sudo -n install -m 0755 -d /etc/apt/keyrings
sudo -n curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo -n chmod 0644 /etc/apt/keyrings/docker.asc
architecture=$(dpkg --print-architecture)
sudo -n tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Architectures: $architecture
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo -n apt-get update -qq
sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo -n systemctl enable --now docker
sudo -n docker version --format '{{.Server.Version}}'
sudo -n docker compose version
