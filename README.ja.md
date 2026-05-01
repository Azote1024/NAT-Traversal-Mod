# NAT Traversal Mod v1.0.0

管理者向けの全体手順です。英語版 `README.md` を正本とし、本ファイルは追従します。

## コンポーネント構成

- `neoforge/` - Minecraft mod（NeoForge 1.21.1）
- `relay-server/` - 軽量TCPリレーサーバー
- `supabase/` - `public.rooms` 用スキーマ/migration

## ドキュメントマップ

- 全体（英語）: `README.md`
- 全体（日本語）: `README.ja.md`
- NeoForge単体（英語）: `neoforge/README.md`
- NeoForge単体（日本語）: `neoforge/README.ja.md`
- Relay単体（英語）: `relay-server/README.md`
- Relay単体（日本語）: `relay-server/README.ja.md`
- Supabase単体（英語）: `supabase/README.md`
- Supabase単体（日本語）: `supabase/README.ja.md`

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

- `relay_priority_mode` は `public_first`（既定）/`relay_first`
- relay ポートは設定で変更可能

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

