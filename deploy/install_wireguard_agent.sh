#!/usr/bin/env bash
set -euo pipefail

binary=/tmp/fastertunnel-wg-agent
environment=/etc/fastertunnel/wg-agent.env

if [[ ! -s "$binary" ]]; then
  echo "Missing Linux agent binary at $binary" >&2
  exit 1
fi
if [[ ! -s "$environment" ]]; then
  echo "Create $environment with FASTATUNNEL_API_URL and WIREGUARD_AGENT_TOKEN first." >&2
  exit 1
fi
if ! systemctl is-active --quiet wg-quick@wg0; then
  echo "wg-quick@wg0 must be active before installing the peer agent." >&2
  exit 1
fi
if ! wg show wg0 listen-port | grep -qx 51820; then
  echo "wg0 must listen on UDP port 51820." >&2
  exit 1
fi
if ! ip -4 -o addr show dev wg0 | awk '{print $4}' | grep -qx '10.66.0.1/24'; then
  echo "wg0 must use 10.66.0.1/24 for the managed peer address pool." >&2
  exit 1
fi

chmod 0600 "$environment"
install -o root -g root -m 0755 "$binary" /usr/local/sbin/fastertunnel-wg-agent
install -o root -g root -m 0644 /tmp/fastertunnel-wg-agent.service /etc/systemd/system/fastertunnel-wg-agent.service
systemctl daemon-reload
systemctl enable --now fastertunnel-wg-agent
systemctl --no-pager --full status fastertunnel-wg-agent
