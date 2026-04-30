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
- 未完了: STUN問い合わせと候補交換ロジック

## 直近マイルストーン (再確認)

1. M1: STUN前安定運用（完了）
2. M2: STUN導入準備（進行中）
   - Resolver境界の維持
   - publisher payload拡張ポイントの維持
3. M3: STUN最小実装（次）
   - STUN問い合わせ
   - `rooms` への候補反映（後方互換を維持）
4. M4: LAN外安定化（後続）
   - テスト結果に基づく調整
   - 必要時のみ relay/TURN 検討

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

`stun_*` は最小STUN実装を含みますが、`stun_enabled=true` で使う場合は
`supabase/migrations/20260501061000_add_stun_candidate_columns.sql` の適用が前提です。

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

1. STUN前安定運用の維持（現状完了）
2. STUN候補データ契約の実DB反映（migration追加、対応）
3. STUN問い合わせの最小実装（無効デフォルトを維持、対応）
4. 候補反映時の解決優先順位を実装
5. LAN外テスト結果で最小調整
