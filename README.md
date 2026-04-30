# NAT Traversal Mod (MVP)

Minecraft Java Edition (NeoForge 1.21.1) 向けの、友人間利用を想定した最小 NAT 越え接続 Mod です。

## 何をする Mod か

- 通常のマルチプレイUIは変更しません
- 接続先ホストが `intercept_host` と完全一致した場合のみ、接続先解決を横取りします
- Supabase REST (`rooms` テーブル) から `host_ip` と `host_port` を取得し、接続先を差し替えます
- 取得失敗時は接続を止めず、元のアドレスで通常接続を継続します
- ホスト側では `rooms` を自動更新し、停止時に `status=closed` へ更新します

## 現在の実装範囲 (MVP)

- Configキー:
  - `supabase_url`, `supabase_key`, `room_name`, `intercept_host`
  - `publish_host_name`, `publish_host_ip` (サーバー側upsert用)
- Mixin対象: `ServerNameResolver#resolveAddress` (client)
- Supabase認証: `apikey` ヘッダのみ
- リトライ: なし（失敗時は即フォールバック）
- 取得条件: `room_name` 完全一致 + `status=open`
- 鮮度条件: `updated_at` が 180 秒以内
- `intercept_host` 比較: 完全一致のみ
- ホスト側更新:
  - サーバー起動時 publish
  - 60秒ごとの定期 publish
  - サーバー停止時 `status=closed` (非同期)
  - publish/close時に `updated_at` を明示更新
- クライアント通知:
  - 接続試行時 (`Server Connector` スレッド) のみ簡易メッセージ表示

データ契約の詳細は `docs/rooms-data-contract.md` を参照してください。

## 必須項目 (先に実装する項目)

1. ルーム公開の継続性（定期 publish）
2. 停止時クリーンアップ（`status=closed`）
3. 参加側の可観測性（クライアントメッセージ）
4. STUN着手前の運用固定（`room_name` 固定、`status` 運用、LAN外試験手順）

## あるとよい項目 (後で実装)

1. 低コストの短期キャッシュ（数秒）
2. ホスト側 room 更新の高度化（差分更新、バックオフ）
3. UI改善（詳細トースト、設定ガイド）
4. `updated_at` の鮮度判定
5. STUN用候補アドレスデータ契約

## 設定ファイル

`runClient` または `runServer` 実行後に以下へ生成されます。

- `neoforge/run/config/nat_traversal_mod-common.toml`

主な設定:

- `supabase_url`
- `supabase_key`
- `room_name`
- `intercept_host`
- `publish_host_name`
- `publish_host_ip`
- `stun_enabled` (将来用、現時点は無効推奨)
- `stun_server` (将来用)
- `stun_timeout_ms` (将来用)

`publish_host_ip` はホスト側で必須です（公開IPまたは到達可能IP）。

`stun_*` 設定は先行追加のみで、現時点では実際のSTUN処理は未実装です。

## 動作確認手順 (最短)

1. ホスト側で `publish_host_name` と `publish_host_ip` を設定
2. ホスト側でサーバーを起動し、`Room published` ログを確認
3. 参加側で `intercept_host` 宛に接続
4. `Resolved room target` ログが出ることを確認

ログ確認ポイント (`neoforge/run/logs/latest.log`):

- `Room published` が出る -> ホスト側upsert成功
- `Room closed` が出る -> 停止時クリーンアップ成功
- `Intercept hit` が出る -> 参加側で横取り発火
- `Resolved room target` が出る -> 差し替え成功
- `Room data is stale` が出る -> ルーム情報が古いためフォールバック
- `Fallback to original target` が出る -> 取得失敗だが通常接続継続

## ビルド

```powershell
Set-Location "C:\Users\nitro\Documents\GitHub\NAT-Traversal-Mod\neoforge"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew.bat --no-daemon compileJava
```

## 最短ロードマップ

1. MVP安定化: 接続ログ整備 (完了)
2. 必須運用: 定期publish + 停止時close + クライアント通知 (対応)
3. 次機能: 低コストの短期キャッシュ (数秒)
4. その次: 更新戦略の高度化
5. STUN問い合わせ実装（候補交換の最小スコープ）
