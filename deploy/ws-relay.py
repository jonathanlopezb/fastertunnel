#!/usr/bin/env python3
"""
fasterTunnel WS-to-SSH Relay Daemon
Intercepts HTTP Upgrade: websocket and CONNECT payloads and bridges to Dropbear SSH (127.0.0.1:2222).
Listens on port 80 (and optionally 8080 via second instance).
"""
import socket
import select
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
        try:
            client_sock.close()
        except Exception:
            pass
        try:
            target_sock.close()
        except Exception:
            pass

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

        if b"Upgrade: websocket" in header or b"upgrade: websocket" in header:
            client_sock.sendall(HTTP_101_RESP)
        elif header.upper().startswith(b"CONNECT"):
            client_sock.sendall(HTTP_200_RESP)
        else:
            client_sock.sendall(HTTP_101_RESP)

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
    print(f"[*] fasterTunnel WS Relay listening on :{LISTEN_PORT} -> {TARGET_HOST}:{TARGET_PORT}")

    while True:
        client_sock, _ = server.accept()
        t = threading.Thread(target=handle_client, args=(client_sock,), daemon=True)
        t.start()

if __name__ == '__main__':
    main()
