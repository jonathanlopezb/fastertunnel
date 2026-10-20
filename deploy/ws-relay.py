#!/usr/bin/env python3
"""
fasterTunnel WS-to-SSH Relay + Landing Page Daemon v3
- WebSocket / CONNECT requests → bridge to Dropbear SSH (127.0.0.1:2222)
- Plain HTTP GET → serve landing page HTML inline
- Handles Cloudflare WebSocket proxying with proper Sec-WebSocket-Accept
"""
import socket
import select
import threading
import base64
import hashlib
import os

LISTEN_PORT = 80
TARGET_HOST = '127.0.0.1'
TARGET_PORT = 2222
BUFFER_SIZE = 65536

WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

HTTP_200_RESP = b"HTTP/1.1 200 Connection Established\r\n\r\n"

# Landing page HTML cargada desde archivo (si existe) o inline
_LANDING_HTML = None

def get_landing_html():
    global _LANDING_HTML
    if _LANDING_HTML is None:
        html_path = os.path.join(os.path.dirname(__file__), 'landing', 'index.html')
        try:
            with open(html_path, 'rb') as f:
                _LANDING_HTML = f.read()
        except Exception:
            _LANDING_HTML = b"""<!DOCTYPE html>
<html><head><meta charset=UTF-8><title>FasterTunnel</title>
<style>body{font-family:sans-serif;background:#0f0f13;color:#eee;text-align:center;padding:4rem}
h1{color:#F97316;font-size:3rem}p{color:#aaa;font-size:1.1rem}</style></head>
<body><h1>&#9889; FasterTunnel</h1>
<p>Internet libre en Colombia &mdash; Sin saldo, sin l&iacute;mites.</p>
<p>Gateway activo &bull; Cloudflare CDN &bull; AWS Ohio</p>
<p><a href="https://github.com/jonathanlopezb/fastertunnel" style="color:#F97316">Descargar APK</a></p>
</body></html>"""
    return _LANDING_HTML

def make_ws_101(sec_key: str) -> bytes:
    """Generate proper WebSocket 101 response with correct Sec-WebSocket-Accept."""
    if sec_key:
        accept = base64.b64encode(
            hashlib.sha1((sec_key + WS_MAGIC).encode()).digest()
        ).decode()
    else:
        accept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
    return (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
    ).encode()

def make_http_200_html(body: bytes) -> bytes:
    return (
        b"HTTP/1.1 200 OK\r\n"
        b"Content-Type: text/html; charset=utf-8\r\n"
        b"Connection: close\r\n"
        b"Cache-Control: public, max-age=3600\r\n"
        b"X-Powered-By: FasterTunnel\r\n"
        b"Content-Length: " + str(len(body)).encode() + b"\r\n"
        b"\r\n" + body
    )

def pipe_sockets(client_sock, target_sock):
    sockets = [client_sock, target_sock]
    try:
        while True:
            r, _, _ = select.select(sockets, [], [], 120)
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
        for s in (client_sock, target_sock):
            try: s.close()
            except Exception: pass

def parse_header_value(headers: bytes, name: str) -> str:
    name_lower = name.lower().encode()
    for line in headers.split(b"\r\n"):
        if b":" in line:
            key, _, val = line.partition(b":")
            if key.strip().lower() == name_lower:
                return val.strip().decode(errors="replace")
    return ""

def handle_client(client_sock):
    try:
        header = b""
        client_sock.settimeout(15.0)
        while b"\r\n\r\n" not in header and len(header) < 8192:
            chunk = client_sock.recv(1024)
            if not chunk:
                break
            header += chunk

        client_sock.settimeout(None)
        if not header:
            client_sock.close()
            return

        header_upper = header.upper()
        is_websocket = b"UPGRADE: WEBSOCKET" in header_upper
        is_connect   = header_upper.lstrip().startswith(b"CONNECT")

        if is_websocket or is_connect:
            # → Tunnel: conectar al SSH relay
            target_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            target_sock.settimeout(10.0)
            target_sock.connect((TARGET_HOST, TARGET_PORT))
            target_sock.settimeout(None)

            if is_websocket:
                sec_key = parse_header_value(header, "Sec-WebSocket-Key")
                client_sock.sendall(make_ws_101(sec_key))
            else:
                client_sock.sendall(HTTP_200_RESP)

            pipe_sockets(client_sock, target_sock)
        else:
            # → Landing page para visitas normales (navegadores, health checks, etc.)
            body = get_landing_html()
            client_sock.sendall(make_http_200_html(body))
            client_sock.close()

    except Exception:
        try: client_sock.close()
        except Exception: pass

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('0.0.0.0', LISTEN_PORT))
    server.listen(500)
    print(f"[*] fasterTunnel WS Relay v3 on :{LISTEN_PORT} → SSH {TARGET_HOST}:{TARGET_PORT}")

    while True:
        client_sock, _ = server.accept()
        t = threading.Thread(target=handle_client, args=(client_sock,), daemon=True)
        t.start()

if __name__ == '__main__':
    main()

