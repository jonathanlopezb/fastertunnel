#!/bin/bash
set -e

# ==============================================================================
# fasterTunnel - AWS Gateway Node Automated Installer
# Installs: Dropbear (SSH:2222), badvpn-udpgw (UDP:7300), and WS-to-SSH Proxy (80/443)
# ==============================================================================

echo "=========================================================="
echo "🚀 INICIANDO INSTALACIÓN DE FASTER TUNNEL GATEWAY"
echo "=========================================================="

export DEBIAN_FRONTEND=noninteractive

# 1. Habilitar IP Forwarding
echo "⚙️ Configurando optimizaciones del kernel e IP Forwarding..."
cat << 'EOF' | sudo tee /etc/sysctl.d/99-fastertunnel.conf
net.ipv4.ip_forward = 1
net.ipv4.conf.all.rp_filter = 0
net.ipv4.conf.default.rp_filter = 0
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
EOF
sudo sysctl --system > /dev/null 2>&1

# 2. Instalar dependencias esenciales
echo "📦 Actualizando paquetes e instalando dependencias..."
sudo apt-get update -y
sudo apt-get install -y dropbear cmake make gcc git python3 python3-pip iptables curl net-tools libssl-dev

# 3. Configurar Dropbear SSH en el puerto 2222 (para que el 22 siga libre para gestión)
echo "🔒 Configurando Dropbear en puerto 2222..."
cat << 'EOF' | sudo tee /etc/default/dropbear
NO_START=0
DROPBEAR_PORT=2222
DROPBEAR_EXTRA_ARGS="-p 2222 -W 65536"
DROPBEAR_BANNER=""
DROPBEAR_RECEIVE_WINDOW=65536
EOF

sudo systemctl restart dropbear
sudo systemctl enable dropbear

# 4. Crear usuario para el túnel
echo "👤 Creando usuario de acceso al túnel..."
if ! id -u fasteruser > /dev/null 2>&1; then
    sudo useradd -M -s /bin/false fasteruser
    echo "fasteruser:fastertunnel2024" | sudo chpasswd
    echo "✅ Usuario 'fasteruser' creado con contraseña 'fastertunnel2024'"
else
    echo "fasteruser:fastertunnel2024" | sudo chpasswd
    echo "ℹ️ Usuario 'fasteruser' actualizado."
fi

# 5. Compilar e Instalar BadVPN (udpgw en puerto 7300)
echo "🎮 Instalando badvpn-udpgw para soporte de juegos online y llamadas UDP..."
cd /tmp
if [ ! -d "badvpn" ]; then
    git clone https://github.com/ambrop72/badvpn.git
fi
cd badvpn
mkdir -p build && cd build
cmake .. -DBUILD_NOTHING_BY_DEFAULT=1 -DBUILD_UDPGW=1 > /dev/null 2>&1
make install > /dev/null 2>&1

cat << 'EOF' | sudo tee /etc/systemd/system/badvpn.service
[Unit]
Description=BadVPN UDPGW Service for FasterTunnel
After=network.target

[Service]
ExecStart=/usr/local/bin/badvpn-udpgw --listen-addr 127.0.0.1:7300 --max-clients 1000 --max-connections-for-client 100
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable badvpn
sudo systemctl restart badvpn

# 6. WebSocket-to-SSH Python Relay (soporta HTTP 80 y 8080)
echo "🌐 Instalando WebSocket-to-SSH Relay en puerto 80..."
cat << 'EOF' | sudo tee /usr/local/bin/ws-relay.py
#!/usr/bin/env python3
"""
fasterTunnel WS-to-SSH Relay Daemon
Intercepts HTTP Upgrade: websocket and CONNECT payloads and bridges to Dropbear SSH (127.0.0.1:2222).
"""
import socket
import select
import sys
import threading

LISTEN_PORT = 80
TARGET_HOST = '127.0.0.1'
TARGET_PORT = 2222
BUFFER_SIZE = 32768

HTTP_200_RESP = b"HTTP/1.1 200 Connection Established\r\n\r\n"
HTTP_101_RESP = (
    b"HTTP/1.1 101 Switching Protocols\r\n"
    b"Upgrade: websocket\r\n"
    b"Connection: Upgrade\r\n"
    b"Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
)

def pipe_sockets(client_sock, target_sock):
    sockets = [client_sock, target_sock]
    try:
        while True:
            r, _, _ = select.select(sockets, [], [], 60)
            if not r:
                break
            for s in r:
                data = s.recv(BUFFER_SIZE)
                if not data:
                    return
                if s is client_sock:
                    target_sock.sendall(data)
                else:
                    client_sock.sendall(data)
    except Exception:
        pass
    finally:
        client_sock.close()
        target_sock.close()

def handle_client(client_sock):
    try:
        header = b""
        client_sock.settimeout(10.0)
        while b"\r\n\r\n" not in header and len(header) < 4096:
            chunk = client_sock.recv(1024)
            if not chunk:
                break
            header += chunk
        
        client_sock.settimeout(None)
        if not header:
            client_sock.close()
            return
            
        target_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        target_sock.connect((TARGET_HOST, TARGET_PORT))

        # Check if WebSocket upgrade or CONNECT
        if b"Upgrade: websocket" in header or b"upgrade: websocket" in header:
            client_sock.sendall(HTTP_101_RESP)
        elif header.startswith(b"CONNECT"):
            client_sock.sendall(HTTP_200_RESP)
        else:
            # Direct HTTP payload handshake
            client_sock.sendall(HTTP_101_RESP)

        pipe_sockets(client_sock, target_sock)
    except Exception:
        client_sock.close()

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('0.0.0.0', LISTEN_PORT))
    server.listen(500)
    print(f"[*] fasterTunnel WS Relay listening on port {LISTEN_PORT} -> {TARGET_HOST}:{TARGET_PORT}")
    
    while True:
        client_sock, _ = server.accept()
        t = threading.Thread(target=handle_client, args=(client_sock,), daemon=True)
        t.start()

if __name__ == '__main__':
    main()
EOF

sudo chmod +x /usr/local/bin/ws-relay.py

cat << 'EOF' | sudo tee /etc/systemd/system/ws-relay.service
[Unit]
Description=fasterTunnel WebSocket-to-SSH Relay
After=network.target dropbear.service

[Service]
ExecStart=/usr/bin/python3 /usr/local/bin/ws-relay.py
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable ws-relay
sudo systemctl restart ws-relay

echo "=========================================================="
echo "✅ INSTALACIÓN COMPLETADA CON ÉXITO"
echo "=========================================================="
echo "Estado de servicios:"
sudo systemctl status dropbear --no-pager | grep "Active:"
sudo systemctl status badvpn --no-pager | grep "Active:"
sudo systemctl status ws-relay --no-pager | grep "Active:"
echo "Puertos activos:"
sudo netstat -tlpn | grep -E '2222|7300|80'
