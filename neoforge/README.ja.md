# neoforge（mcmod）

NeoForge mod プロジェクト単体のビルド/実行ガイドです。

全体手順は以下を参照してください。

- 英語: `../README.md`
- 日本語: `../README.ja.md`

## 前提

- Java 21
- Gradle Wrapper（`gradlew.bat`）

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

- ドキュメント中の relay ポートは例示であり、設定で変更可能


