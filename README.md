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
- `public_endpoint` が有効なら優先採用、なければ `host_ip:host_port`
- `relay_status=ready` かつ relayクライアント有効時は relay経路を次点で採用
- `intercept_host` 比較: 完全一致のみ
- ホスト側更新:
  - サーバー起動時 publish
  - 60秒ごとの定期 publish
  - サーバー停止時 `status=closed` (非同期)
  - publish/close時に `updated_at` を明示更新
- クライアント通知:
  - 接続試行時 (`Server Connector` スレッド) のみ簡易メッセージ表示

データ契約の詳細は `docs/rooms-data-contract.md` を参照してください。
全体マイルストーンは `docs/milestones.md` を参照してください。

## 現状ステータス

- 完了: `intercept_host` 横取り / Supabase解決 / 失敗時フォールバック
- 完了: サーバー側 `rooms` publish/close と 60秒定期更新
- 完了: `updated_at` 180秒鮮度判定
- 完了: `rooms` データ契約の文書化 (`docs/rooms-data-contract.md`)
- 完了: STUN最小実装（publish拡張 + `public_endpoint` 優先解決）
- 進行中: LAN外安定化（判定ラベル運用）
- 予定: シンプル中継サーバーソフト構築（`relay-server/`）

## 直近マイルストーン (再確認)

1. M1: STUN前安定運用（完了）
2. M2: STUN導入準備（完了）
   - Resolver境界の維持
   - publisher payload拡張ポイントの維持
3. M3: STUN最小実装（完了）
   - STUN問い合わせ
   - `rooms` への候補反映（後方互換を維持）
4. M4: LAN外安定化（進行中）
   - テスト結果に基づく調整
5. M5: 友人向け運用固定化（次）
   - README手順の最小化
   - 判定ラベル運用の固定
6. M6: シンプル中継サーバーソフト構築（予定）
   - `relay-server/` を新規ワークスペースとして利用
7. M7: UDP hole punching + QUICブリッジ（実験ブランチ）

## 設定ファイル

`runClient` / `runServer` 実行後、設定は役割ごとに分離されます。

- `nat_traversal_mod-common.toml` (共通)
- `nat_traversal_mod-client.toml` (クライアント用)
- `nat_traversal_mod-server.toml` (サーバー用)

主な設定:

共通 (`nat_traversal_mod-common.toml`)
- `supabase_url`
- `supabase_key`
- `room_name`
- `stun_enabled` (STUN拡張を有効化)
- `stun_server` (STUNサーバー)
- `stun_timeout_ms` (STUNタイムアウト)
- `relay_endpoint` (自前relayの接続先 host:port)
- `relay_connect_endpoint` (互換用の旧relay接続先)
- `relay_token` (relay接続用トークン)

サーバー (`nat_traversal_mod-server.toml`)
- `publish_host_name`
- `publish_host_ip`
- `relay_publish_endpoint` (roomsへ公開するrelay接続先 host:port)
- `relay_connect_endpoint_server` (サーバー側connector用 relay接続先)
- `relay_status` (`ready` or `down`)

クライアント (`nat_traversal_mod-client.toml`)
- `intercept_host`
- `relay_connect_endpoint_client` (クライアント側connector用 relay接続先)
- `relay_client_connector_enabled` (ローカルrelayクライアント利用ON/OFF)
- `relay_client_local_port` (ローカルrelayクライアント待受ポート)
- `relay_priority_mode` (`public_first` / `relay_first`)

`publish_host_ip` はホスト側で必須です（公開IPまたは到達可能IP）。

`stun_*` は最小STUN実装を含みますが、`stun_enabled=true` で使う場合は
`supabase/migrations/20260501061000_add_stun_candidate_columns.sql` の適用が前提です。

`relay_*` を使う場合は、`relay_client_connector_enabled=true` と
ローカルrelayクライアントの起動が前提です（未起動時は接続失敗になります）。

`relay_publish_endpoint` は未設定時に `relay_endpoint` を使います。
`relay_connect_endpoint_server` は未設定時に `relay_connect_endpoint` を使います。
`relay_connect_endpoint_client` は未設定時に `relay_connect_endpoint` を使います。

`relay_priority_mode` は既定で `public_first` です。`relay_first` にすると
`relay_status=ready` 時に relay経路を `public_endpoint` より先に試します。

運用ルール:

- token/IP/DDNS などの機微値は `run/config` でのみ管理し、docs にはプレースホルダで記載する。

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
- `Use local relay client connector` が出る -> relay経路採用
- `Fallback to original target` が出る -> 取得失敗だが通常接続継続

## ビルド

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew.bat --no-daemon compileJava
```

## 最短ロードマップ

1. STUN前安定運用の維持（現状完了）
2. STUN候補データ契約の実DB反映（migration追加、対応）
3. STUN問い合わせの最小実装（無効デフォルトを維持、対応）
4. 候補反映時の解決優先順位を実装
5. LAN外テスト結果で最小調整（進行中）
6. 友人向け運用手順の固定
7. シンプル中継サーバーソフト構築（`relay-server/`）
8. UDP hole punching + QUICブリッジを別ブランチで実験

