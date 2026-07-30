"""Small allowlist-only CONNECT proxy used by the agent network namespace.

The proxy deliberately has no HTTP forwarding mode: agent traffic must use TLS,
so the only policy decision made here is whether a public host may be reached.
"""

from __future__ import annotations

import argparse
import ipaddress
import os
import select
import socket
import socketserver
import sys
import threading
from collections.abc import Iterable


MAX_HEADER = 16 * 1024
MAX_PENDING = 1024 * 1024
CONNECT_TIMEOUT = 15
IDLE_TIMEOUT = 15 * 60
MAX_CONNECTIONS = 64


def normalize_host(host: str) -> str:
    return host.rstrip(".").encode("idna").decode("ascii").lower()


def parse_authority(authority: str) -> tuple[str, int]:
    if authority.startswith("["):
        end = authority.find("]")
        if end < 0 or len(authority) <= end + 2 or authority[end + 1] != ":":
            raise ValueError("invalid IPv6 authority")
        return authority[1:end], int(authority[end + 2 :])

    host, separator, port = authority.rpartition(":")
    if not separator or not host:
        raise ValueError("CONNECT target must include a port")
    return host, int(port)


def allowed_host(host: str, rules: Iterable[str]) -> bool:
    normalized = normalize_host(host)
    try:
        ipaddress.ip_address(normalized)
    except ValueError:
        pass
    else:
        return False

    for raw_rule in rules:
        rule = normalize_host(raw_rule.removeprefix("*.").removeprefix("."))
        if normalized == rule or normalized.endswith(f".{rule}"):
            return True
    return False


def public_addresses(host: str, port: int) -> list[tuple[int, int, int, tuple]]:
    addresses: list[tuple[int, int, int, tuple]] = []
    seen: set[tuple[int, tuple]] = set()
    for family, socktype, proto, _, sockaddr in socket.getaddrinfo(
        host, port, type=socket.SOCK_STREAM
    ):
        address = ipaddress.ip_address(sockaddr[0])
        key = family, sockaddr
        if address.is_global and key not in seen:
            addresses.append((family, socktype, proto, sockaddr))
            seen.add(key)
    return addresses


def connect_public(host: str, port: int) -> socket.socket:
    last_error: OSError | None = None
    for family, socktype, proto, sockaddr in public_addresses(host, port):
        upstream = socket.socket(family, socktype, proto)
        upstream.settimeout(CONNECT_TIMEOUT)
        try:
            upstream.connect(sockaddr)
            upstream.settimeout(None)
            return upstream
        except OSError as error:
            last_error = error
            upstream.close()

    if last_error is not None:
        raise last_error
    raise OSError("target has no public address")


def relay(left: socket.socket, right: socket.socket, initial: bytes) -> None:
    left.setblocking(False)
    right.setblocking(False)
    sockets = [left, right]
    peers = {left: right, right: left}
    readable = {left, right}
    pending = {left: bytearray(), right: bytearray(initial)}

    while readable or any(pending.values()):
        read_candidates = [
            source
            for source in readable
            if len(pending[peers[source]]) < MAX_PENDING
        ]
        write_candidates = [
            destination for destination in sockets if pending[destination]
        ]
        ready_read, ready_write, exceptional = select.select(
            read_candidates, write_candidates, sockets, IDLE_TIMEOUT
        )
        if exceptional or not (ready_read or ready_write):
            return

        for destination in ready_write:
            try:
                sent = destination.send(pending[destination])
            except (BlockingIOError, InterruptedError):
                continue
            except OSError:
                return
            if sent == 0:
                return
            del pending[destination][:sent]
            source = peers[destination]
            if source not in readable and not pending[destination]:
                try:
                    destination.shutdown(socket.SHUT_WR)
                except OSError:
                    return

        for source in ready_read:
            try:
                data = source.recv(64 * 1024)
            except (BlockingIOError, InterruptedError):
                continue
            except OSError:
                return
            destination = peers[source]
            if data:
                pending[destination].extend(data)
            else:
                readable.remove(source)
                if not pending[destination]:
                    try:
                        destination.shutdown(socket.SHUT_WR)
                    except OSError:
                        return


class ProxyServer(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True
    allow_reuse_address = False

    def __init__(self, socket_path: str, rules: list[str]):
        self.rules = rules
        self.slots = threading.BoundedSemaphore(MAX_CONNECTIONS)
        super().__init__(socket_path, ProxyHandler)

    def process_request(self, request: socket.socket, client_address: object) -> None:
        if not self.slots.acquire(blocking=False):
            request.sendall(b"HTTP/1.1 503 Too Many Connections\r\n\r\n")
            request.close()
            return
        super().process_request(request, client_address)

    def process_request_thread(
        self, request: socket.socket, client_address: object
    ) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


class ProxyHandler(socketserver.BaseRequestHandler):
    server: ProxyServer

    def deny(self, status: str, detail: str) -> None:
        print(f"agent proxy denied: {detail}", file=sys.stderr)
        self.request.sendall(f"HTTP/1.1 {status}\r\n\r\n".encode("ascii"))

    def handle(self) -> None:
        header = bytearray()
        while b"\r\n\r\n" not in header:
            chunk = self.request.recv(4096)
            if not chunk:
                return
            header.extend(chunk)
            if len(header) > MAX_HEADER:
                self.deny("431 Request Header Fields Too Large", "oversized header")
                return

        raw_header, initial = bytes(header).split(b"\r\n\r\n", 1)
        try:
            request_line = raw_header.split(b"\r\n", 1)[0].decode("ascii")
            method, authority, version = request_line.split(" ")
            host, port = parse_authority(authority)
        except (UnicodeDecodeError, ValueError):
            self.deny("400 Bad Request", "malformed CONNECT request")
            return

        if method != "CONNECT" or not version.startswith("HTTP/"):
            self.deny("405 Method Not Allowed", "only CONNECT is supported")
            return
        if port != 443:
            self.deny("403 Forbidden", f"{host}:{port} uses a non-TLS port")
            return
        if not allowed_host(host, self.server.rules):
            self.deny("403 Forbidden", f"{host} is not allowlisted")
            return

        try:
            upstream = connect_public(normalize_host(host), port)
        except OSError as error:
            self.deny("502 Bad Gateway", f"{host}: {error}")
            return

        print(f"agent proxy connected: {normalize_host(host)}:{port}", file=sys.stderr)
        with upstream:
            self.request.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            relay(self.request, upstream, initial)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--socket", required=True)
    parser.add_argument("--allow", action="append", default=[])
    return parser.parse_args()


def main() -> None:
    args = arguments()
    if not args.allow:
        raise SystemExit("at least one --allow rule is required")

    socket_path = os.path.abspath(args.socket)
    if os.path.lexists(socket_path):
        os.unlink(socket_path)

    server = ProxyServer(socket_path, args.allow)
    os.chmod(socket_path, 0o600)
    try:
        server.serve_forever(poll_interval=0.25)
    finally:
        server.server_close()
        if os.path.lexists(socket_path):
            os.unlink(socket_path)


if __name__ == "__main__":
    main()
