# neoforge (mcmod)

Component guide for building and running the NeoForge mod project.

For full stack setup, see:

- English: `../README.md`
- Japanese: `../README.ja.md`

Operational template (Windows client + Ubuntu server):

- `../docs/windows-client-ubuntu-server-operations-template.md`
- `../docs/windows-client-ubuntu-server-operations-template.ja.md` (Japanese)

## Requirements

- Java 21
- Gradle wrapper (`gradlew.bat`)
- Git (only needed when `../ref/quicprotocolsupport/libs` is missing)

## Deployment Assumption

- Client machine: Windows
- Server machine: Ubuntu

Examples and config guidance below assume this split.

## First Clone Bootstrap

- If QUIC jars do not exist under `../ref/quicprotocolsupport/libs`, Gradle will auto-bootstrap them by cloning:
  - `https://codeberg.org/tesinormed/QuicProtocolSupport.git`
- Run this once after clone:

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat --no-daemon compileJava
```

- Optional overrides:
  - custom repo URL: `-PquicProtocolSupportRepoUrl=<git-url>`
  - verbose QUIC transport logs: `-PnatQuicVerboseLogs=true`

## Build

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat --no-daemon compileJava
```

## Run Server

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat runServer
```

## Run Client

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat runClient
```

## Config Files

- `run/config/nat_traversal_mod-common.toml`
- `run/config/nat_traversal_mod-server.toml`
- `run/config/nat_traversal_mod-client.toml`

## Config Guide

### `nat_traversal_mod-common.toml`

- `supabase.url`, `supabase.api_key`: required for room read/write
- `mode.room_name`: shared room key across server and client
- `mode.connect_strategy`: one of `tcp_only`, `quic_first`, `relay_first`, `tcp_quic_relay`
- `stun.enabled`, `stun.server`, `stun.timeout_ms`: optional endpoint discovery hints

### `nat_traversal_mod-server.toml`

- `publish.host_name`, `publish.host_ip`: host metadata published to room
  - when `stun.enabled=true` and STUN succeeds, `publish.host_ip` is auto-overridden by the detected public IP (configured value is used as fallback)
- `relay.publish_endpoint`: relay endpoint advertised to peers
- `relay.connect_endpoint`: relay endpoint host side connector actually dials
- `quic.publish_endpoint`: QUIC endpoint published for peer attempts
- `quic.cert_file`, `quic.key_file`: required only when using QUIC server tunnel with certificate mode
  - Example: `quic/cert.pem`, `quic/key.pem` (resolved relative to `run/config`)

### `nat_traversal_mod-client.toml`

- `mode.intercept_host`: exact target host (or host:port) to hook in client resolver
- `relay.connect_endpoint`: relay endpoint client connector dials
- `relay.local_port`: local loopback port used by relay connector
- `quic.enabled`, `quic.attempts`, `quic.attempt_interval_ms`: QUIC fallback behavior
- `routing.tcp_attempts`, `routing.stage_reset_ms`: planner behavior in `tcp_quic_relay`

Recommended: use a symbolic host (for example `play.mc.local`) for `mode.intercept_host` so config stays stable even if public IP changes.

## Example Profiles

1. Mapped public port (server can expose TCP)
   - `mode.connect_strategy = "tcp_quic_relay"`
   - client connects to `play.mc.local`
   - router/NAT forwards public `:25565` to server
2. No port mapping (relay-first practical mode)
   - `mode.connect_strategy = "relay_first"`
   - set `relay.publish_endpoint` + `relay.connect_endpoint` to reachable relay address
3. Hybrid trial mode
   - keep `tcp_quic_relay`
   - low `quic.attempts` (1-2), relay always configured as safety net

## Troubleshooting Checklist

1. Config format uses section keys (`[supabase]`, `[mode]`, ...) and not legacy flat keys
2. `mode.intercept_host` exactly matches the host users enter in multiplayer screen
3. Relay address in server/client config is reachable from both sides
4. On Ubuntu server, open required firewall ports (`ufw`/cloud SG)
5. Check `run/logs/latest.log` for `Intercept hit`, `Room published`, and route fallback messages

## Notes

- Relay local port values are configurable.
- `mode.connect_strategy` supports `tcp_only`, `quic_first`, `relay_first`, `tcp_quic_relay`.
- `quic.tls_mode=ca_or_pinned` uses CA validation and accepts self-signed only when `quic.cert_fingerprint_sha256` matches.
- `quic.tls_mode=insecure_trust_all` is for development only.
- With `quic.tls_mode=insecure_trust_all`, `quic.cert_fingerprint_sha256` can stay empty.
- For tethered/external relay fallback tests, `relay.connect_endpoint` must be reachable from external clients.
- If `quic.publish_endpoint` uses a non-forwarded port, QUIC direct failure is expected; relay fallback is the primary success path.

