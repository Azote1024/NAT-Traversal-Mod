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
- `migrations/20260503123000_quic_sessions_punch_observability.sql`
  - `public.quic_sessions` に観測列（`host_probe_sent_at`, `attempt_id`, `last_error_code`）を追加
- `migrations/20260503152000_nat_routing_phase1.sql`
  - `public.rooms` / `public.quic_sessions` に NAT routing 列を追加
  - `public.quic_peer_attempts`（クライアント単位試行記録）を新設
- `migrations/20260503190000_quic_peer_attempts_punch_sync_phase1.sql`
  - `public.quic_peer_attempts` に punch 同期メタ列を追加
  - クライアント公開endpoint・同期トークン・パンチウィンドウ観測列を導入

## 現在のスキーマ範囲

- `public.rooms`: room単位のhost公開・relay情報
- `public.quic_sessions`: room単位のQUIC状態・経路決定情報
- `public.quic_peer_attempts`: クライアント単位の試行履歴

## HTTPステータス運用（現行）

- 基本方針は `2xx` を成功扱い。
- QUICセッション参照（`/rest/v1/quic_sessions`）:
  - `400`: スキーマ不一致（旧select列で1回だけ再試行）
  - `404`: エンドポイント未提供（migration未適用またはテーブル経路不一致）
- QUIC試行履歴 upsert（`/rest/v1/quic_peer_attempts`）:
  - `400`/`404`: エンドポイント未提供（機能劣化として扱い、接続処理は継続）
- room参照（`/rest/v1/rooms`）:
  - `404`: エンドポイント未提供（通常ターゲットへフォールバック）

## Option A: クラウドSupabase

1. Supabaseプロジェクト作成/選択
2. migration を順番にすべて適用
3. `rooms` / `quic_sessions` / `quic_peer_attempts` が存在することを確認
4. URL/key は `neoforge/run/config/nat_traversal_mod-common.toml` にローカル設定

## Option B: ローカルSupabase CLI

```powershell
Set-Location "<repo-root>"
supabase start
```

その後、CLI運用に合わせて migration を適用（`db reset` / `migration up` など）。


