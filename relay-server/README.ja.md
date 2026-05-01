# relay-server

友人利用向けの最小TCPリレーサーバーです。

全体手順は以下を参照してください。

- 英語: `../README.md`
- 日本語: `../README.ja.md`

## 目的（最小）

- `host` と `client` 間の生TCPバイト中継
- 依存最小のシンプル実装
- `rooms` の relay 列と連携

## プロトコル（最小）

`HELLO <token> <role>\n`

- `token`: `[A-Za-z0-9_-]{1,64}`
- `role`: `host` または `client`

## 設定

`relay_config.toml` の主要キー:

- `host`
- `port`（既定/例: `40000`。設定で変更可能）
- `handshake_timeout`

## 起動（uv）

```powershell
Set-Location "<repo-root>"
Set-Location ".\relay-server"
uv run python .\start.py
```

## スモークテスト（uv）

```powershell
Set-Location "<repo-root>"
Set-Location ".\relay-server"
uv run python .\test_relay.py
```

## Ubuntu運用メモ（UFW）

`<relay-port>` を設定値に置き換えて許可してください（`40000` は既定例）。

```bash
sudo ufw allow <relay-port>/tcp
sudo ufw status
```

