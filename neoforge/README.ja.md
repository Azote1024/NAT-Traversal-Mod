# neoforge（mcmod）

NeoForge mod プロジェクト単体のビルド/実行ガイドです。

全体手順は以下を参照してください。

- 英語: `../README.md`
- 日本語: `../README.ja.md`

運用テンプレート（Windows client + Ubuntu server）:

- `../docs/windows-client-ubuntu-server-operations-template.md`
- `../docs/windows-client-ubuntu-server-operations-template.ja.md`

## 前提

- Java 21
- Gradle Wrapper（`gradlew.bat`）
- Git（`../ref/quicprotocolsupport/libs` が無い場合のみ必要）

## 想定デプロイ

- クライアント端末: Windows
- サーバー端末: Ubuntu

以下の設定例はこの前提で記載しています。

## クローン直後の初期化

- `../ref/quicprotocolsupport/libs` に QUIC jar が無い場合、Gradle が以下を clone して自動補完します。
  - `https://codeberg.org/tesinormed/QuicProtocolSupport.git`
- クローン直後は次を1回実行してください。

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat --no-daemon compileJava
```

- 任意オプション:
  - clone URL 上書き: `-PquicProtocolSupportRepoUrl=<git-url>`
  - QUIC詳細ログを有効化: `-PnatQuicVerboseLogs=true`

## ビルド

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat --no-daemon compileJava
```

## サーバー起動

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat runServer
```

## クライアント起動

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat runClient
```

## 設定ファイル

- `run/config/nat_traversal_mod-common.toml`
- `run/config/nat_traversal_mod-server.toml`
- `run/config/nat_traversal_mod-client.toml`

## Configガイド

### `nat_traversal_mod-common.toml`

- `supabase.url`, `supabase.api_key`: room情報の読み書きに必須
- `mode.room_name`: サーバー/クライアントで共有する room キー
- `mode.connect_strategy`: `tcp_only` / `quic_first` / `relay_first` / `tcp_quic_relay`
- `stun.enabled`, `stun.server`, `stun.timeout_ms`: 公開 endpoint 推定の補助

### `nat_traversal_mod-server.toml`

- `publish.host_name`, `publish.host_ip`: roomに出すホスト情報
- `relay.publish_endpoint`: peer に提示する relay 到達先
- `relay.connect_endpoint`: ホスト側 relay コネクタが実際に接続する先
- `quic.publish_endpoint`: QUIC P2P 試行用に公開する先
- `quic.cert_file`, `quic.key_file`: 証明書モードでQUICサーバートンネルを使う場合に必要

### `nat_traversal_mod-client.toml`

- `mode.intercept_host`: クライアント resolver が介入する完全一致ホスト（または host:port）
- `relay.connect_endpoint`: クライアント側 relay コネクタの接続先
- `relay.local_port`: relay コネクタのローカル loopback ポート
- `quic.enabled`, `quic.attempts`, `quic.attempt_interval_ms`: QUIC 試行の挙動
- `routing.tcp_attempts`, `routing.stage_reset_ms`: `tcp_quic_relay` 時の段階制御

推奨: `mode.intercept_host` は `play.mc.local` のような意味名にして、公開IP変更の影響を受けにくくします。

## 構成プロファイル例

1. 公開ポート転送あり（直接接続優先）
   - `mode.connect_strategy = "tcp_quic_relay"`
   - クライアント接続先は `play.mc.local`
   - ルーター/NATで公開 `:25565` をサーバーへ転送
2. 公開ポート転送なし（relay優先）
   - `mode.connect_strategy = "relay_first"`
   - `relay.publish_endpoint` と `relay.connect_endpoint` を到達可能 relay に固定
3. ハイブリッド検証モード
   - `tcp_quic_relay` を維持
   - `quic.attempts` は少なめ（1-2）、relay を常時有効化

## トラブルシュート（初動チェック）

1. 設定が section 形式（`[supabase]`, `[mode]` など）で、旧フラットキーを使っていないか
2. `mode.intercept_host` がマルチプレイ画面の接続先と完全一致しているか
3. relay アドレスがサーバー/クライアント双方から到達可能か
4. Ubuntu 側 firewall（`ufw` 等）とクラウドSGで必要ポートが開いているか
5. `run/logs/latest.log` に `Intercept hit` / `Room published` / fallbackログが出るか

## 注意

- relay のローカルポートは設定で変更可能
- `mode.connect_strategy` は `tcp_only` / `quic_first` / `relay_first` / `tcp_quic_relay`
- `quic.tls_mode=ca_or_pinned` は CA 検証に加え、`quic.cert_fingerprint_sha256` 一致時のみ自己署名を許可
- `quic.tls_mode=insecure_trust_all` は開発用途のみ


