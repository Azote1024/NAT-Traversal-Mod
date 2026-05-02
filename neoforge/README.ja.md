# neoforge（mcmod）

NeoForge mod プロジェクト単体のビルド/実行ガイドです。

全体手順は以下を参照してください。

- 英語: `../README.md`
- 日本語: `../README.ja.md`

## 前提

- Java 21
- Gradle Wrapper（`gradlew.bat`）
- Git（`../ref/quicprotocolsupport/libs` が無い場合のみ必要）

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

## 注意

- relay ポートは設定で変更可能
- `relay_priority_mode` は `quic_first` も利用可能
- `quic_tls_mode=ca_or_pinned` は CA 検証に加え、`quic_cert_fingerprint_sha256` 一致時のみ自己署名を許可
- `quic_tls_mode=insecure_trust_all` は開発用途のみ


