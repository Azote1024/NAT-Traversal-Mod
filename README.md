# NAT Traversal Mod v1.0.0

English source-of-truth tutorial for administrators running this stack.

Japanese guide follows this document: `README.ja.md`.

## Components

- `neoforge/` - Minecraft mod (NeoForge 1.21.1)
- `relay-server/` - lightweight TCP relay service
- `supabase/` - signaling schema/migrations for `public.rooms`

## Assumed Platform

- Client: Windows 10/11 (Minecraft + NeoForge dev runtime)
- Server: Ubuntu 22.04/24.04 (Minecraft dedicated server runtime)
- Relay: Ubuntu (same LAN as server, or public VM)

This guide assumes "client on Windows" and "server on Ubuntu" as the default operations model.

## Documentation Map

- Full stack (EN): `README.md`
- Full stack (JA): `README.ja.md`
- NeoForge component (EN): `neoforge/README.md`
- NeoForge component (JA): `neoforge/README.ja.md`
- Relay component (EN): `relay-server/README.md`
- Relay component (JA): `relay-server/README.ja.md`
- Supabase component (EN): `supabase/README.md`
- Supabase component (JA): `supabase/README.ja.md`
- Ops template (Windows client + Ubuntu server): `docs/windows-client-ubuntu-server-operations-template.md`
- Ops template (JA): `docs/windows-client-ubuntu-server-operations-template.ja.md`

## Quickstart (Admin)

1. Apply `supabase/migrations/20260430165635_remote_schema.sql`.
2. Start relay server.
3. Configure `neoforge/run/config/*.toml` (common/server/client).
4. Start Minecraft server (`runServer`) and client (`runClient`).
5. Verify relay-path logs.

### PowerShell

```powershell
Set-Location "<repo-root>"
Set-Location ".\relay-server"
uv run python .\start.py
```

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat runServer
```

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat runClient
```

## Config Split

Generated under `neoforge/run/config/`:

- `nat_traversal_mod-common.toml`
- `nat_traversal_mod-server.toml`
- `nat_traversal_mod-client.toml`

Key notes:

- `mode.connect_strategy` supports `tcp_only`, `quic_first`, `relay_first`, `tcp_quic_relay`.
- `quic.client_local_port` is internal-default only (not user-configurable).
- Relay-related local port values remain configurable.

### File Roles

- `nat_traversal_mod-common.toml`: cross-side settings (`supabase.*`, `mode.room_name`, `stun.*`, shared `relay.token`)
- `nat_traversal_mod-server.toml`: host publish/routing settings (`publish.*`, server-side `relay.*`, server-side `quic.*`)
- `nat_traversal_mod-client.toml`: interceptor and local routing behavior (`mode.intercept_host`, client-side `relay.*`, client-side `quic.*`, `routing.*`)

### Key Reference (Most Important)

- `mode.intercept_host`: exact host or host:port to intercept on client (recommended symbolic host like `play.mc.local`)
- `publish.host_ip`: fallback publish IP when room does not contain usable endpoint fields
- `relay.publish_endpoint`: endpoint written to room by host (what peers should try)
- `relay.connect_endpoint`: endpoint directly contacted by host/client relay connector
- `quic.publish_endpoint`: host QUIC endpoint published for P2P attempt
- `supabase.url` / `supabase.api_key`: signaling backend base URL and key

### Recommended Topology Examples

1. Port mapping available (prefer direct first)
   - `mode.connect_strategy = "tcp_quic_relay"`
   - Server maps public `:25565` to local MC port
   - `mode.intercept_host = "play.mc.local"` on client
2. No port mapping (relay-heavy)
   - `mode.connect_strategy = "relay_first"` or `tcp_quic_relay`
   - Set both `relay.publish_endpoint` and `relay.connect_endpoint` to reachable relay address
3. Mixed network / unstable NAT
   - Keep `tcp_quic_relay`
   - Set `quic.attempts` low (1-2) and ensure relay endpoint is always valid

For concrete NeoForge-side examples, see `neoforge/README.md`.

## Troubleshooting (First 5 Checks)

1. `run/config` file split is correct (no legacy flat keys like `supabase_url`)
2. `mode.intercept_host` exactly matches what client enters in server list
3. `relay.publish_endpoint` and `relay.connect_endpoint` point to a truly reachable address from each side
4. Ubuntu firewall/NAT allows required inbound traffic (MC/relay/QUIC ports)
5. Confirm logs in `neoforge/run/logs/latest.log` for `Intercept hit`, `Room published`, and fallback behavior

## Verification Markers

Server (`neoforge/run/logs/latest.log`):

- `Room published`
- `Relay host connector paired`
- `Room closed`

Client (`neoforge/run/logs/latest.log`):

- `Intercept hit`
- `Use local relay client connector` or `Use public_endpoint from room`
- `Resolved room target`

Relay stdout:

- `waiting counterpart token=...`
- `pairing token=...`

## Build

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat --no-daemon compileJava
```

## Operations Policy

- Team/operations rules are documented in `docs/security-operations.md`.
- Deployment playbook template is available at `docs/windows-client-ubuntu-server-operations-template.md`.

