# NAT Traversal Mod (MVP) - 日本語ガイド

このドキュメントは管理者向けの全体手順です。

## 構成

- `neoforge/` : Minecraft Mod 本体
- `relay-server/` : TCP リレーサーバー
- `supabase/` : シグナリング用スキーマ/migration

## クイックスタート

1. Supabase で `supabase/migrations/*.sql` を適用
2. `relay-server` を起動
3. `neoforge/run/config/` の3ファイル（common/server/client）を設定
4. `runServer` -> `runClient` の順で起動
5. ログで relay 経路を確認

## コマンド例（PowerShell）

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

## セキュリティ

- 実URL / 実キー / 実IP / 実トークンは docs に書かない
- 機微値は各環境の `neoforge/run/config/*` でのみ管理
- 例示ポート（例: `40000`）は設定で変更可能

