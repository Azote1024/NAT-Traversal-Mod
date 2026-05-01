# supabase (signaling)

Supabase is used as signaling storage only (`public.rooms`).

For full stack setup, see:

- English: `../README.md`
- Japanese: `../README.ja.md`

## Purpose

- Store room metadata for host/client rendezvous.
- Keep signaling separate from Minecraft traffic.
- Support STUN and relay metadata with backward compatibility.

## Migration Files

- `migrations/20260430165635_remote_schema.sql`
  - Base `public.rooms` table
- `migrations/20260501061000_add_stun_candidate_columns.sql`
  - STUN columns: `nat_method`, `public_endpoint`, `candidates`
- `migrations/20260501100000_add_relay_columns.sql`
  - Relay columns: `relay_endpoint`, `relay_token`, `relay_status`

## Option A: Cloud Supabase

1. Create/open a Supabase project.
2. Apply migration SQL in order.
3. Confirm all required columns exist on `public.rooms`.
4. Put URL/key into `neoforge/run/config/nat_traversal_mod-common.toml` (local only).

## Option B: Local Supabase CLI

```powershell
Set-Location "<repo-root>"
supabase start
```

Then apply migrations with your CLI flow (`db reset`, `migration up`, etc.).

