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

## Option A: Cloud Supabase

1. Create/open a Supabase project.
2. Apply `migrations/20260430165635_remote_schema.sql`.
3. Confirm all required columns exist on `public.rooms`.
4. Put URL/key into `neoforge/run/config/nat_traversal_mod-common.toml` (local only).

## Option B: Local Supabase CLI

```powershell
Set-Location "<repo-root>"
supabase start
```

Then apply migrations with your CLI flow (`db reset`, `migration up`, etc.).

