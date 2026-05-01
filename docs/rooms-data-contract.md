# roomsデータ契約 (STUN前準備)

## 1. 目的

- 現行MVPの `rooms` 利用仕様を固定し、実装と運用のブレを防ぐ。
- 将来のSTUN拡張キーを「未実装・後方互換あり」で先に定義する。

## 2. 現行で必須のキー (実装済み)

- `room_name: text` (PK)
- `host_name: text`
- `host_ip: text`
- `host_port: int`
- `status: text` (`open` / `closed`)
- `updated_at: timestamptz`

## 3. 現行クライアント解決ルール (実装済み)

1. `room_name` 完全一致
2. `status=open` のみ対象
3. `updated_at` が TTL 180秒以内
4. `public_endpoint` が有効なら優先採用
5. `relay_status=ready` かつ `relay_endpoint` が有効なら次点で採用
6. それ以外は `host_ip:host_port` を接続先として採用
7. どこかで失敗したら元接続へフォールバック

## 4. 将来STUN拡張キー (未実装・任意)

- `nat_method: text`
  - 例: `direct`, `stun`
- `public_endpoint: text`
  - 例: `198.51.100.12:25565`
- `candidates: jsonb`
  - 例: endpoint候補配列
- `relay_endpoint: text`
  - 例: `relay.example.com:40000`
- `relay_token: text`
  - 例: セッション識別トークン
- `relay_status: text`
  - 例: `ready`, `down`

現時点の実装では上記キーを参照しない。存在しなくても正常動作すること。

現時点の最小STUN実装では、`stun_enabled=true` のときホスト側publishで上記キーを書き込む。
クライアント側解決はまだ既存の `host_ip:host_port` 優先のまま維持する。

カラム追加migration:

- `supabase/migrations/20260501061000_add_stun_candidate_columns.sql`
- `supabase/migrations/20260501100000_add_relay_columns.sql`

## 5. 後方互換ルール

- 既存クライアントは `host_ip` / `host_port` / `status` / `updated_at` のみで解決できること。
- STUN拡張キーは必須化しない。
- 拡張キーの値が不正でも、現行解決経路は壊さない。

## 6. ログ方針

- `info`: `Intercept hit`, `Room data is fresh`, `Resolved room target`
- `warn`: `Room data is stale`, `Room resolve failed`, `Fallback to original target`
- 失敗時は必ずフォールバックを明示する。

## 7. 実装参照

- `neoforge/src/main/java/com/azote/nat_traversal_mod/net/SupabaseRoomsClient.java`
- `neoforge/src/main/java/com/azote/nat_traversal_mod/net/SupabaseRoomsPublisher.java`
- `neoforge/src/main/java/com/azote/nat_traversal_mod/mixin/ServerNameResolverMixin.java`

