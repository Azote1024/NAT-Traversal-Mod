# supabase（シグナリング）

Supabase はシグナリング情報（`public.rooms`）の保存にのみ使用します。

全体手順は以下を参照してください。

- 英語: `../README.md`
- 日本語: `../README.ja.md`

## 目的

- host/client の待ち合わせ情報を保存
- Minecraft本体通信とシグナリングを分離
- STUN/relay列を含むv1.0.0単一スキーマを運用

## migration

- `migrations/20260430165635_remote_schema.sql`（v1.0.0基準スキーマ）
- `migrations/20260502090000_quic_sessions.sql`（QUIC専用 `public.quic_sessions`）
  - one-shot hole punching 最小項目（`punch_status`, `punch_token`, `client_punch_sent_at`）を含む

## Option A: クラウドSupabase

1. Supabaseプロジェクト作成/選択
2. `migrations/20260430165635_remote_schema.sql` を適用
3. `public.rooms` の必要列を確認
4. URL/key は `neoforge/run/config/nat_traversal_mod-common.toml` にローカル設定

## Option B: ローカルSupabase CLI

```powershell
Set-Location "<repo-root>"
supabase start
```

その後、CLI運用に合わせて migration を適用（`db reset` / `migration up` など）。


