#!/usr/bin/env python3
"""
fasterTunnel WS-to-SSH Relay Daemon v2
Intercepts HTTP Upgrade: websocket, CONNECT, and plain GET payloads and bridges to Dropbear SSH (127.0.0.1:2222).
Handles Cloudflare WebSocket proxying (X-Forwarded-For, CF-Connecting-IP headers).
Listens on port 80.
"""
import socket
import select
import threading
import base64
import hashlib

LISTEN_PORT = 80
TARGET_HOST = '127.0.0.1'
TARGET_PORT = 2222
BUFFER_SIZE = 65536

WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

HTTP_200_RESP = b"HTTP/1.1 200 Connection Established\r\n\r\n"

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
        try:
            client_sock.close()
        except Exception:
            pass
        try:
            target_sock.close()
        except Exception:
            pass

def parse_header_value(headers: bytes, name: str) -> str:
    """Extract a header value (case-insensitive) from raw HTTP headers."""
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

        target_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        target_sock.settimeout(10.0)
        target_sock.connect((TARGET_HOST, TARGET_PORT))
        target_sock.settimeout(None)

        header_upper = header.upper()
        if b"UPGRADE: WEBSOCKET" in header_upper:
            # Proper WebSocket handshake with correct Sec-WebSocket-Accept
            sec_key = parse_header_value(header, "Sec-WebSocket-Key")
            client_sock.sendall(make_ws_101(sec_key))
        elif header_upper.startswith(b"CONNECT"):
            client_sock.sendall(HTTP_200_RESP)
        else:
            # Plain GET / POST — treat as WebSocket tunnel anyway (Hamara-style)
            sec_key = parse_header_value(header, "Sec-WebSocket-Key")
            client_sock.sendall(make_ws_101(sec_key))

        pipe_sockets(client_sock, target_sock)
    except Exception:
        try:
            client_sock.close()
        except Exception:
            pass

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('0.0.0.0', LISTEN_PORT))
    server.listen(500)
    print(f"[*] fasterTunnel WS Relay v2 listening on :{LISTEN_PORT} -> {TARGET_HOST}:{TARGET_PORT}")

    while True:
        client_sock, addr = server.accept()
        t = threading.Thread(target=handle_client, args=(client_sock,), daemon=True)
        t.start()

if __name__ == '__main__':
    main()

