# supabase（シグナリング）

Supabase はシグナリング情報（`public.rooms`）の保存にのみ使用します。

全体手順は以下を参照してください。

- 英語: `../README.md`
- 日本語: `../README.ja.md`

## 目的

- host/client の待ち合わせ情報を保存
- Minecraft本体通信とシグナリングを分離
- STUN/relay拡張を後方互換で運用

## migration

- `migrations/20260430165635_remote_schema.sql`
- `migrations/20260501061000_add_stun_candidate_columns.sql`
- `migrations/20260501100000_add_relay_columns.sql`

## Option A: クラウドSupabase

1. Supabaseプロジェクト作成/選択
2. migration SQL を順番に適用
3. `public.rooms` の必要列を確認
4. URL/key は `neoforge/run/config/nat_traversal_mod-common.toml` にローカル設定

## Option B: ローカルSupabase CLI

```powershell
Set-Location "<repo-root>"
supabase start
```

その後、CLI運用に合わせて migration を適用（`db reset` / `migration up` など）。


