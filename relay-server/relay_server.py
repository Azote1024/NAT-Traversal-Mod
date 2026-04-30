import argparse
import re
import socket
import threading
import time
from dataclasses import dataclass
from typing import Dict, Optional, Tuple

TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
ROLE_HOST = "host"
ROLE_CLIENT = "client"


@dataclass
class PendingConn:
    sock: socket.socket
    addr: Tuple[str, int]
    remainder: bytes
    connected_at: float


class SimpleRelayServer:
    def __init__(self, host: str, port: int, handshake_timeout: float = 10.0):
        self.host = host
        self.port = port
        self.handshake_timeout = handshake_timeout
        self._lock = threading.Lock()
        self._sessions: Dict[str, Dict[str, PendingConn]] = {}
        self._server_socket: Optional[socket.socket] = None
        self._running = False

    def serve_forever(self) -> None:
        self._server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server_socket.bind((self.host, self.port))
        self._server_socket.listen(64)
        self._running = True
        print(f"[relay] listening on {self.host}:{self.port}")

        try:
            while self._running:
                try:
                    client, addr = self._server_socket.accept()
                except OSError:
                    break
                thread = threading.Thread(target=self._handle_connection, args=(client, addr), daemon=True)
                thread.start()
        finally:
            self.shutdown()

    def shutdown(self) -> None:
        self._running = False
        if self._server_socket is not None:
            try:
                self._server_socket.close()
            except OSError:
                pass
            self._server_socket = None

        with self._lock:
            sessions = list(self._sessions.items())
            self._sessions.clear()

        for _, session in sessions:
            for side in (ROLE_HOST, ROLE_CLIENT):
                pending = session.get(side)
                if pending:
                    self._safe_close(pending.sock)

    def _handle_connection(self, sock: socket.socket, addr: Tuple[str, int]) -> None:
        sock.settimeout(self.handshake_timeout)
        try:
            hello_line, remainder = self._read_line(sock, 256)
            token, role = self._parse_hello(hello_line)
            if token is None or role is None:
                print(f"[relay] invalid hello from {addr}: {hello_line!r}")
                self._safe_close(sock)
                return

            pending = PendingConn(sock=sock, addr=addr, remainder=remainder, connected_at=time.time())
            counterpart = self._register_and_try_pair(token, role, pending)
            if counterpart is None:
                return

            self._start_bridge(token, pending, counterpart)
        except (OSError, ValueError) as exc:
            print(f"[relay] handshake failed from {addr}: {exc}")
            self._safe_close(sock)

    def _register_and_try_pair(self, token: str, role: str, pending: PendingConn) -> Optional[PendingConn]:
        opposite = ROLE_CLIENT if role == ROLE_HOST else ROLE_HOST
        with self._lock:
            session = self._sessions.setdefault(token, {})
            old = session.get(role)
            if old is not None:
                self._safe_close(old.sock)
            session[role] = pending

            counterpart = session.get(opposite)
            if counterpart is None:
                print(f"[relay] waiting counterpart token={token} role={role} from {pending.addr}")
                return None

            del self._sessions[token]
            return counterpart

    def _start_bridge(self, token: str, first: PendingConn, second: PendingConn) -> None:
        first.sock.settimeout(None)
        second.sock.settimeout(None)
        print(f"[relay] pairing token={token} {first.addr} <-> {second.addr}")

        stop_event = threading.Event()
        t1 = threading.Thread(target=self._pipe, args=(first.sock, second.sock, first.remainder, stop_event, token, "A->B"), daemon=True)
        t2 = threading.Thread(target=self._pipe, args=(second.sock, first.sock, second.remainder, stop_event, token, "B->A"), daemon=True)
        t1.start()
        t2.start()

        def wait_and_close() -> None:
            t1.join()
            t2.join()
            self._safe_close(first.sock)
            self._safe_close(second.sock)
            print(f"[relay] closed token={token}")

        closer = threading.Thread(target=wait_and_close, daemon=True)
        closer.start()

    def _pipe(
        self,
        src: socket.socket,
        dst: socket.socket,
        initial: bytes,
        stop_event: threading.Event,
        token: str,
        direction: str,
    ) -> None:
        try:
            if initial:
                dst.sendall(initial)

            while not stop_event.is_set():
                data = src.recv(65536)
                if not data:
                    break
                dst.sendall(data)
        except OSError as exc:
            print(f"[relay] stream error token={token} dir={direction}: {exc}")
        finally:
            stop_event.set()

    @staticmethod
    def _read_line(sock: socket.socket, max_len: int) -> Tuple[str, bytes]:
        data = bytearray()
        while len(data) < max_len:
            chunk = sock.recv(1024)
            if not chunk:
                raise ValueError("connection closed before hello line")
            data.extend(chunk)
            idx = data.find(b"\n")
            if idx != -1:
                line = bytes(data[:idx]).decode("utf-8", errors="replace").strip()
                remainder = bytes(data[idx + 1 :])
                return line, remainder
        raise ValueError("hello line too long")

    @staticmethod
    def _parse_hello(line: str) -> Tuple[Optional[str], Optional[str]]:
        parts = line.split(" ")
        if len(parts) != 3 or parts[0] != "HELLO":
            return None, None

        token = parts[1].strip()
        role = parts[2].strip().lower()
        if role not in (ROLE_HOST, ROLE_CLIENT):
            return None, None
        if not TOKEN_PATTERN.match(token):
            return None, None
        return token, role

    @staticmethod
    def _safe_close(sock: socket.socket) -> None:
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            sock.close()
        except OSError:
            pass


def main() -> None:
    parser = argparse.ArgumentParser(description="Simple TCP relay server for NAT-Traversal-Mod")
    parser.add_argument("--host", default="0.0.0.0", help="Listen host (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=40000, help="Listen port (default: 40000)")
    parser.add_argument("--handshake-timeout", type=float, default=10.0, help="HELLO timeout seconds")
    args = parser.parse_args()

    server = SimpleRelayServer(args.host, args.port, args.handshake_timeout)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("[relay] stopping...")
        server.shutdown()


if __name__ == "__main__":
    main()

