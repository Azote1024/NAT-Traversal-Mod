# supabase (signaling)

Supabase is used as signaling storage only (`public.rooms`).

For full stack setup, see:

- English: `../README.md`
- Japanese: `../README.ja.md`

## Purpose

- Store room metadata for host/client rendezvous.
- Keep signaling separate from Minecraft traffic.
- Use a single v1.0.0 schema including STUN and relay metadata.

## Migration Files

- `migrations/20260430165635_remote_schema.sql`
  - v1.0.0 baseline `public.rooms` table including STUN/relay columns
- `migrations/20260502090000_quic_sessions.sql`
  - dedicated `public.quic_sessions` table for QUIC signaling
  - includes minimal one-shot hole punching fields (`punch_status`, `punch_token`, `client_punch_sent_at`)
- `migrations/20260503123000_quic_sessions_punch_observability.sql`
  - extends `public.quic_sessions` with observability columns (`host_probe_sent_at`, `attempt_id`, `last_error_code`)
- `migrations/20260503152000_nat_routing_phase1.sql`
  - extends `public.rooms` and `public.quic_sessions` with NAT routing metadata
  - creates `public.quic_peer_attempts` for client-scoped attempt records
- `migrations/20260503190000_quic_peer_attempts_punch_sync_phase1.sql`
  - extends `public.quic_peer_attempts` with punch sync metadata
  - adds per-attempt fields for client endpoint, sync token, and punch window observability

## Current Schema Scope

- `public.rooms`: room-level host publish and relay metadata
- `public.quic_sessions`: room-level QUIC session and route decision state
- `public.quic_peer_attempts`: client-scoped attempt timeline for concurrent joins

## HTTP Status Handling (Current)

- General REST operations treat `2xx` as success.
- QUIC session query (`/rest/v1/quic_sessions`):
  - `400`: schema mismatch (retry once with legacy select columns)
  - `404`: endpoint unavailable (migration missing or wrong table path)
- QUIC peer attempts upsert (`/rest/v1/quic_peer_attempts`):
  - `400`/`404`: endpoint unavailable (feature degraded, client proceeds without peer-attempt timeline)
- Room fetch (`/rest/v1/rooms`):
  - `404`: endpoint unavailable (fallback to original target path)

## Option A: Cloud Supabase

1. Create/open a Supabase project.
2. Apply all migrations in order.
3. Confirm required tables exist: `rooms`, `quic_sessions`, `quic_peer_attempts`.
4. Put URL/key into `neoforge/run/config/nat_traversal_mod-common.toml` (local only).

## Option B: Local Supabase CLI

```powershell
Set-Location "<repo-root>"
supabase start
```

Then apply migrations with your CLI flow (`db reset`, `migration up`, etc.).

