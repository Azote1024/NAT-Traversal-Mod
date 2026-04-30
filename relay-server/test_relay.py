import socket
import threading
import time

from relay_server import SimpleRelayServer


def _recv_exact(sock: socket.socket, expected_len: int, timeout: float = 3.0) -> bytes:
    sock.settimeout(timeout)
    chunks = []
    size = 0
    while size < expected_len:
        chunk = sock.recv(expected_len - size)
        if not chunk:
            break
        chunks.append(chunk)
        size += len(chunk)
    return b"".join(chunks)


def main() -> None:
    server = SimpleRelayServer("127.0.0.1", 40123)
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()
    time.sleep(0.2)

    host = socket.create_connection(("127.0.0.1", 40123), timeout=3.0)
    host.sendall(b"HELLO smoke-token host\n")

    client = socket.create_connection(("127.0.0.1", 40123), timeout=3.0)
    client.sendall(b"HELLO smoke-token client\n")

    host.sendall(b"abc")
    if _recv_exact(client, 3) != b"abc":
        raise RuntimeError("host->client relay failed")

    client.sendall(b"xyz")
    if _recv_exact(host, 3) != b"xyz":
        raise RuntimeError("client->host relay failed")

    host.close()
    client.close()
    server.shutdown()
    print("relay smoke test passed")


if __name__ == "__main__":
    main()

