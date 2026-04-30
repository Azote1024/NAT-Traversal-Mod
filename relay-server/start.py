import os
import tomllib
from pathlib import Path

from relay_server import SimpleRelayServer


def load_config(config_path: Path) -> tuple[str, int, float]:
    with config_path.open("rb") as f:
        data = tomllib.load(f)

    host = str(data.get("host", "0.0.0.0")).strip() or "0.0.0.0"
    port = int(data.get("port", 40000))
    handshake_timeout = float(data.get("handshake_timeout", 10.0))

    if port < 1 or port > 65535:
        raise ValueError(f"Invalid port: {port}")
    if handshake_timeout <= 0:
        raise ValueError(f"Invalid handshake_timeout: {handshake_timeout}")

    return host, port, handshake_timeout


def main() -> None:
    base_dir = Path(__file__).resolve().parent
    config_name = os.environ.get("RELAY_CONFIG", "relay_config.toml")
    config_path = (base_dir / config_name).resolve()

    if not config_path.exists():
        raise FileNotFoundError(f"Config file not found: {config_path}")

    host, port, handshake_timeout = load_config(config_path)
    print(f"[relay] config: host={host} port={port} handshake_timeout={handshake_timeout}")

    server = SimpleRelayServer(host, port, handshake_timeout)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("[relay] stopping...")
        server.shutdown()


if __name__ == "__main__":
    main()

