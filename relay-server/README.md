# relay-server

Simple self-hosted TCP relay server for friends-only usage.

## Goal (minimum)

- Provide raw TCP byte relay between one `host` and one `client`.
- Keep implementation simple and dependency-free.
- Integrate with `rooms` relay fields:
  - `relay_endpoint`
  - `relay_token`
  - `relay_status`

## Protocol (minimum)

Each side opens TCP connection and sends one line:

`HELLO <token> <role>\n`

- `token`: `[A-Za-z0-9_-]{1,64}`
- `role`: `host` or `client`

When both roles with same token are connected, server starts bidirectional byte relay.

## Files

- `relay_server.py`: relay server implementation
- `test_relay.py`: local smoke test
- `requirements.txt`: no external dependencies

## Config

- Default config file: `relay_config.toml`
- Minimal keys:
  - `host`
  - `port`
  - `handshake_timeout`

## Run (uv)

```powershell
Set-Location "<repo-root>"
Set-Location ".\relay-server"
uv run python .\start.py
```

Optional: use another config file via env var.

```powershell
Set-Location "<repo-root>"
Set-Location ".\relay-server"
$env:RELAY_CONFIG = "relay_config.toml"
uv run python .\start.py
```

## Smoke Test (uv)

```powershell
Set-Location "<repo-root>"
Set-Location ".\relay-server"
uv run python .\test_relay.py
```

## Ubuntu Notes (firewall)

- If relay server runs on Ubuntu with UFW, allow relay port explicitly.

```bash
sudo ufw allow 40000/tcp
sudo ufw status
```

