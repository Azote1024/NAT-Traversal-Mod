# NAT Traversal Mod v1.0.0

管理者向けの全体手順です。英語版 `README.md` を正本とし、本ファイルは追従します。

## コンポーネント構成

- `neoforge/` - Minecraft mod（NeoForge 1.21.1）
- `relay-server/` - 軽量TCPリレーサーバー
- `supabase/` - `public.rooms` 用スキーマ/migration

## 想定プラットフォーム

- クライアント: Windows 10/11（Minecraft + NeoForge 実行）
- サーバー: Ubuntu 22.04/24.04（Minecraft Dedicated Server 実行）
- Relay: Ubuntu（サーバー同一LANまたは公開VM）

本ガイドは「クライアント=Windows」「サーバー=Ubuntu」を基本前提にしています。

## ドキュメントマップ

- 全体（英語）: `README.md`
- 全体（日本語）: `README.ja.md`
- NeoForge単体（英語）: `neoforge/README.md`
- NeoForge単体（日本語）: `neoforge/README.ja.md`
- Relay単体（英語）: `relay-server/README.md`
- Relay単体（日本語）: `relay-server/README.ja.md`
- Supabase単体（英語）: `supabase/README.md`
- Supabase単体（日本語）: `supabase/README.ja.md`
- 運用テンプレ（Windows client + Ubuntu server）: `docs/windows-client-ubuntu-server-operations-template.md`
- 運用テンプレ（日本語）: `docs/windows-client-ubuntu-server-operations-template.ja.md`
- 接続経路テスト手順（日本語）: `docs/tcp-quic-relay-test-procedure.ja.md`

## クイックスタート（管理者）

1. `supabase/migrations/20260430165635_remote_schema.sql` を適用
2. relay-server を起動
3. `neoforge/run/config/*.toml`（common/server/client）を設定
4. Minecraft server (`runServer`) と client (`runClient`) を起動
5. relay経路ログを確認

### PowerShell例

```powershell
Set-Location "<repo-root>"
Set-Location ".\relay-server"
uv run python .\start.py
```

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat runServer
```

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat runClient
```

## 設定分離

`neoforge/run/config/` 配下:

- `nat_traversal_mod-common.toml`
- `nat_traversal_mod-server.toml`
- `nat_traversal_mod-client.toml`

補足:

- `mode.connect_strategy` は `tcp_only` / `quic_first` / `relay_first` / `tcp_quic_relay`
- `quic.client_local_port` は内部デフォルト専用（ユーザー設定不可）
- relay のローカルポートは設定で変更可能

### 各ファイルの役割

- `nat_traversal_mod-common.toml`: 共通設定（`supabase.*`, `mode.room_name`, `stun.*`, 共通 `relay.token`）
- `nat_traversal_mod-server.toml`: ホスト公開/到達設定（`publish.*`, サーバー側 `relay.*`, サーバー側 `quic.*`）
- `nat_traversal_mod-client.toml`: クライアント介入とローカル経路設定（`mode.intercept_host`, クライアント側 `relay.*`, クライアント側 `quic.*`, `routing.*`）

### 主要キー（優先）

- `mode.intercept_host`: クライアントが介入する完全一致ホスト（`play.mc.local` のような意味名を推奨）
- `publish.host_ip`: room内 endpoint が使えない場合のフォールバック公開IP
- `relay.publish_endpoint`: ホストが room に公開する relay 到達先
- `relay.connect_endpoint`: ホスト/クライアントの relay コネクタが直接接続する先
- `quic.publish_endpoint`: QUIC P2P 用にホストが公開するエンドポイント
- `supabase.url` / `supabase.api_key`: シグナリング先URLとAPIキー

### 推奨構成例

1. ポートマッピングあり（直接接続優先）
   - `mode.connect_strategy = "tcp_quic_relay"`
   - サーバーは公開 `:25565` をローカルMCポートへ転送
   - クライアントは `mode.intercept_host = "play.mc.local"`
2. ポートマッピングなし（relay重視）
   - `mode.connect_strategy = "relay_first"` または `tcp_quic_relay`
   - `relay.publish_endpoint` / `relay.connect_endpoint` を到達可能 relay に固定
3. 混在ネットワーク・不安定NAT
   - `tcp_quic_relay` を維持
   - `quic.attempts` を低め（1-2）にし、relay側を常に有効にする

NeoForge 側の具体例は `neoforge/README.ja.md` を参照してください。

## 初動トラブルシュート（まず5項目）

1. `run/config` が新形式（section形式）で、旧キー（例: `supabase_url`）が混在していないか
2. `mode.intercept_host` がクライアント接続先と完全一致しているか
3. `relay.publish_endpoint` / `relay.connect_endpoint` が双方から到達可能か
4. Ubuntu 側 firewall/NAT が MC/relay/QUIC ポートを許可しているか
5. `neoforge/run/logs/latest.log` に `Intercept hit` / `Room published` / fallbackログが出ているか

## 検証ログ

サーバー（`neoforge/run/logs/latest.log`）:

- `Room published`
- `Relay host connector paired`
- `Room closed`

クライアント（`neoforge/run/logs/latest.log`）:

- `Intercept hit`
- `Use local relay client connector` または `Use public_endpoint from room`
- `Resolved room target`

relay標準出力:

- `waiting counterpart token=...`
- `pairing token=...`

## ビルド

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat --no-daemon compileJava
```

## 運用ポリシー

- 運用ルールは `docs/security-operations.md` を参照してください。
- デプロイ手順テンプレートは `docs/windows-client-ubuntu-server-operations-template.md` を参照してください。
- 日本語テンプレートは `docs/windows-client-ubuntu-server-operations-template.ja.md` を参照してください。

