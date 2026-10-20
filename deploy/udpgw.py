#!/usr/bin/env python3
"""
fasterTunnel UDP Gateway (udpgw) - Python implementation
Compatible with badvpn-udpgw protocol used by HTTP injector / VPN clients.

Listens on TCP 7300 (default) and proxies UDP datagrams to/from the internet.
The badvpn-udpgw protocol is a simple framed protocol:
  - 2 bytes: frame length (little-endian, includes these 2 bytes)
  - 1 byte:  flags
  - 2 bytes: client ID (little-endian)
  - 2 bytes: remote port (big-endian)
  - 4 bytes: remote IPv4 address (or 16 for IPv6)
  - variable: UDP payload

Flags:
  BADVPN_CLIENT_FLAG_KEEPALIVE = (1<<0) -> keepalive, no payload
  BADVPN_CLIENT_FLAG_IPV6       = (1<<1) -> IPv6 address
"""

import socket
import struct
import threading
import select
import logging
import sys
import time

LISTEN_HOST = '127.0.0.1'
LISTEN_PORT = 7300
MAX_CLIENTS = 1000
BUFFER_SIZE = 65535
KEEPALIVE_INTERVAL = 30  # seconds

logging.basicConfig(
    level=logging.INFO,
    format='[%(asctime)s] %(levelname)s %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
log = logging.getLogger('udpgw')

FLAG_KEEPALIVE = 1 << 0
FLAG_IPV6      = 1 << 1

def parse_frame(data):
    """Parse a badvpn-udpgw frame. Returns (flags, client_id, addr, port, payload) or None."""
    if len(data) < 9:
        return None
    frame_len = struct.unpack_from('<H', data, 0)[0]
    if len(data) < frame_len:
        return None
    flags     = data[2]
    client_id = struct.unpack_from('<H', data, 3)[0]

    if flags & FLAG_IPV6:
        if len(data) < 23:
            return None
        addr_bytes = data[5:21]
        addr = socket.inet_ntop(socket.AF_INET6, addr_bytes)
        port = struct.unpack_from('>H', data, 21)[0]
        payload = data[23:frame_len]
    else:
        addr_bytes = data[5:9]
        addr = socket.inet_ntoa(addr_bytes)
        port = struct.unpack_from('>H', data, 9)[0]
        payload = data[11:frame_len]

    return flags, client_id, addr, port, payload

def build_frame(client_id, addr, port, payload):
    """Build a badvpn-udpgw response frame."""
    try:
        addr_bytes = socket.inet_aton(addr)  # IPv4
        header = struct.pack('<HBH', 0, 0, client_id) + addr_bytes + struct.pack('>H', port)
    except OSError:
        # IPv6
        addr_bytes = socket.inet_pton(socket.AF_INET6, addr)
        header = struct.pack('<HBH', 0, FLAG_IPV6, client_id) + addr_bytes + struct.pack('>H', port)

    frame = header + payload
    frame_len = len(frame) + 2  # +2 for the length field itself
    return struct.pack('<H', frame_len) + frame[2:]  # patch length

def build_frame_v2(client_id, addr, port, payload):
    """Build response frame, properly."""
    try:
        addr_bytes = socket.inet_aton(addr)
        is_ipv6 = False
    except OSError:
        addr_bytes = socket.inet_pton(socket.AF_INET6, addr)
        is_ipv6 = True

    flags = FLAG_IPV6 if is_ipv6 else 0
    if is_ipv6:
        inner = struct.pack('BH', flags, client_id) + addr_bytes + struct.pack('>H', port) + payload
    else:
        inner = struct.pack('BH', flags, client_id) + addr_bytes + struct.pack('>H', port) + payload

    frame_len = len(inner) + 2
    return struct.pack('<H', frame_len) + inner

def handle_client(tcp_conn, addr):
    """Handle one VPN client TCP connection."""
    log.info(f"Client connected: {addr}")
    udp_sockets = {}  # client_id -> (udp_sock, remote_addr_port)
    recv_buf = b''
    tcp_conn.settimeout(120.0)

    try:
        while True:
            try:
                chunk = tcp_conn.recv(4096)
            except socket.timeout:
                break
            if not chunk:
                break
            recv_buf += chunk

            while len(recv_buf) >= 2:
                frame_len = struct.unpack_from('<H', recv_buf, 0)[0]
                if frame_len < 2 or frame_len > 65535:
                    recv_buf = b''
                    break
                if len(recv_buf) < frame_len:
                    break  # wait for more data

                frame_data = recv_buf[:frame_len]
                recv_buf = recv_buf[frame_len:]

                parsed = parse_frame(frame_data)
                if not parsed:
                    continue

                flags, client_id, remote_addr, remote_port, payload = parsed

                # Keepalive: just echo it back
                if flags & FLAG_KEEPALIVE:
                    tcp_conn.sendall(frame_data)
                    continue

                # Get or create UDP socket for this client_id
                if client_id not in udp_sockets:
                    try:
                        udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                        udp_sock.settimeout(30.0)
                        udp_sockets[client_id] = (udp_sock, (remote_addr, remote_port))
                        # Start reply reader thread
                        t = threading.Thread(
                            target=udp_reply_reader,
                            args=(udp_sock, tcp_conn, client_id),
                            daemon=True
                        )
                        t.start()
                    except Exception as e:
                        log.warning(f"UDP socket error for client {client_id}: {e}")
                        continue

                udp_sock, _ = udp_sockets[client_id]
                try:
                    udp_sock.sendto(payload, (remote_addr, remote_port))
                except Exception as e:
                    log.debug(f"UDP send error: {e}")

    except Exception as e:
        log.debug(f"Client {addr} handler error: {e}")
    finally:
        for cid, (us, _) in udp_sockets.items():
            try:
                us.close()
            except Exception:
                pass
        try:
            tcp_conn.close()
        except Exception:
            pass
        log.info(f"Client disconnected: {addr}")

def udp_reply_reader(udp_sock, tcp_conn, client_id):
    """Read UDP replies and forward back to VPN client over TCP."""
    try:
        while True:
            try:
                data, (from_addr, from_port) = udp_sock.recvfrom(BUFFER_SIZE)
            except socket.timeout:
                break
            except Exception:
                break
            if not data:
                break
            frame = build_frame_v2(client_id, from_addr, from_port, data)
            try:
                tcp_conn.sendall(frame)
            except Exception:
                break
    except Exception:
        pass

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((LISTEN_HOST, LISTEN_PORT))
    server.listen(MAX_CLIENTS)
    log.info(f"fasterTunnel udpgw listening on {LISTEN_HOST}:{LISTEN_PORT}")
    log.info("UDP Gateway ready: supports gaming, VoIP, WhatsApp calls")

    while True:
        try:
            client_sock, client_addr = server.accept()
            t = threading.Thread(
                target=handle_client,
                args=(client_sock, client_addr),
                daemon=True
            )
            t.start()
        except KeyboardInterrupt:
            log.info("Shutting down udpgw...")
            break
        except Exception as e:
            log.error(f"Accept error: {e}")

if __name__ == '__main__':
    main()
