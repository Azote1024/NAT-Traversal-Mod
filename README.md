# NAT Traversal Mod (MVP)

English source-of-truth tutorial for administrators running this stack.

Japanese guide follows this document: `README.ja.md`.

## Components

- `neoforge/` - Minecraft mod (NeoForge 1.21.1)
- `relay-server/` - lightweight TCP relay service
- `supabase/` - signaling schema/migrations for `public.rooms`

## Documentation Map

- Full stack (EN): `README.md`
- Full stack (JA): `README.ja.md`
- NeoForge component (EN): `neoforge/README.md`
- NeoForge component (JA): `neoforge/README.ja.md`
- Relay component (EN): `relay-server/README.md`
- Relay component (JA): `relay-server/README.ja.md`
- Supabase component (EN): `supabase/README.md`
- Supabase component (JA): `supabase/README.ja.md`

## Quickstart (Admin)

1. Apply Supabase migrations in `supabase/migrations/*.sql`.
2. Start relay server.
3. Configure `neoforge/run/config/*.toml` (common/server/client).
4. Start Minecraft server (`runServer`) and client (`runClient`).
5. Verify relay-path logs.

### PowerShell example

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

- `relay_priority_mode` supports `public_first` (default) and `relay_first`.
- Relay port values shown in docs are examples; they are configurable.

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

