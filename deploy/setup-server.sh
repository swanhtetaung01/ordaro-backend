#!/usr/bin/env bash
# One-time preparation of a fresh Ubuntu 24.04 server (the EC2 instance): Docker, a swap file so
# the builds fit in 2 GB of memory, automatic security updates, and the shop's timezone for logs.
# Run as the default `ubuntu` user:  bash ordaro/ordaro-backend/deploy/setup-server.sh
set -euo pipefail

echo "== packages"
sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get -y upgrade
sudo DEBIAN_FRONTEND=noninteractive apt-get -y install docker.io docker-compose-v2 git curl openssl unattended-upgrades
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"

echo "== swap (2 GB): building the images needs more memory than a small server has"
if ! swapon --show | grep -q /swapfile; then
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

echo "== automatic security updates"
sudo dpkg-reconfigure -f noninteractive unattended-upgrades

echo "== timezone: Asia/Yangon, so logs and backup times read like the shop's clock"
sudo timedatectl set-timezone Asia/Yangon

echo
echo "Done. Log out and back in once (so 'docker' works without sudo), then continue with"
echo "step 6 of deploy/README.md."
