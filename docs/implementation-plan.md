# NAT Traversal Mod 詳細計画書 (MVP)

## 1. 目的

- ポート開放不可環境でも、友人が Minecraft マルチプレイへ参加できるようにする。
- 既存UIの接続フローは維持し、指定ホスト接続時のみ Mod が介入する。

## 2. 設計方針

- 最小構成を優先し、処理は `Config(common/server/client)` / `Resolver Mixin` / `Supabase Client` / `Server Publisher` / `Relay Connector` に限定。
- Supabase はシグナリング用途のみ。Minecraft 通信本体は既存経路を利用。
- 失敗時は接続中断せずフォールバックを優先。

## 3. MVP スコープ

- 参加側: `intercept_host` 完全一致時のみ横取り
- 参加側: `room_name` 固定値で `rooms` 参照
- 参加側: 取得項目は `host_ip` / `host_port` のみ
- 参加側: `updated_at` も参照し、固定TTL(180秒)で鮮度判定
- 参加側: 取得失敗時の再試行は行わず即フォールバック
- 参加側: `stun_*` 設定で最小STUN経路を利用可能
- 参加側: `status=open` 条件でクローズ済みルームを除外
- ホスト側: サーバー起動時に `rooms` を upsert
- ホスト側: 60秒ごとに定期 upsert
- ホスト側: サーバー停止時に `status=closed` (非同期)
- ホスト側: publish/close時に `updated_at` を明示更新
- 認証ヘッダは `apikey` のみ

## 4. 必須項目 (実装順)

1. ルーム公開の継続性（定期 publish）(完了)
2. 停止時クリーンアップ（`status=closed`）(完了)
3. 参加側の可観測性（クライアントメッセージ）(完了)

## 5. あるとよい項目 (後で実装)

1. 低コストの短期キャッシュ (数秒)
2. ホスト側 room 更新の高度化（差分更新やバックオフ）
3. クライアント通知UIの改善（トーストなど）

## 5.5 STUN着手前の必須準備 (実装順)

1. `rooms` の運用ルール固定
   - `status=open/closed` の運用を厳守
   - `room_name` は固定値運用
2. 失敗時挙動の固定
   - 取得失敗時は即フォールバック（再試行なし）
   - ログは `warn` で統一
3. LAN外試験の標準化
   - テザリング構成での成功/失敗判定を手順化

## 5.6 STUN前にTodo化する項目 (非必須)

1. `updated_at` 鮮度TTLの可変化（現状は固定180秒）
2. ルーム候補（candidate）カラムの追加設計
3. 再試行・バックオフの段階的導入

## 5.7 直近マイルストーン

1. M1: STUN前安定運用（完了）
   - `intercept_host` 横取り
   - `rooms` publish/close
   - `updated_at` 鮮度判定
2. M2: STUN導入準備（完了）
   - `rooms` 契約の固定
   - resolver/publisherの拡張ポイント維持
3. M3: STUN最小導入（完了）
   - STUN問い合わせ
   - candidate/public endpoint の保存
4. M4: LAN外安定化（進行中）
   - 実測でのパラメータ調整
5. M5: 友人向け運用固定化（次）
   - 標準運用モードの一本化
   - 判定ラベル運用の固定
6. M6: シンプル中継サーバーソフト構築（進行中）
   - `relay-server/` ワークスペース追加
   - relay経路導入（後方互換維持）
   - server/client 分離設定で運用

### M6 実装優先度（動くもの優先）

- MUST
  1. relay情報の publish 連携（`relay_endpoint` / `relay_token` / `relay_status`）(完了)
  2. relay経路のE2E接続確認（`relay_status=ready`）(完了)
  3. READMEの最小運用手順更新（進行中）
- TODO（後回し）
  1. `relay_token` TTL失効とセッション掃除
  2. relay運用ログの整形・ローテーション
  3. 接続上限/レート制御

## 6. 実装マップ

- `neoforge/src/main/java/com/azote/nat_traversal_mod/Config.java`
  - `COMMON` / `SERVER` / `CLIENT` キー定義
- `neoforge/src/main/java/com/azote/nat_traversal_mod/mixin/ServerNameResolverMixin.java`
  - `ServerNameResolver#resolveAddress` に注入、クライアント通知
- `neoforge/src/main/java/com/azote/nat_traversal_mod/net/SupabaseRoomsClient.java`
  - Supabase REST問い合わせ、最小JSON解析
- `neoforge/src/main/java/com/azote/nat_traversal_mod/net/SupabaseRoomsPublisher.java`
  - サーバー起動時/定期 publish、停止時 close
  - STUN/relay拡張項目のpublish
- `neoforge/src/main/java/com/azote/nat_traversal_mod/net/RelayHostConnector.java`
  - サーバー側 relay 接続（host role）
- `neoforge/src/main/java/com/azote/nat_traversal_mod/net/RelayClientConnectorManager.java`
  - クライアント側 local relay connector
- `neoforge/src/main/resources/nat_traversal_mod.mixins.json`
  - client mixin 登録

## 6.5 データ契約

- `docs/rooms-data-contract.md`
  - `rooms` の現行必須キーと将来STUN拡張キー(未実装)を定義

## 7. 成功判定

- ホスト側で `Room published` ログが出る
- ホスト停止時に `Room closed` ログが出る
- `stop` 実行後にサーバープロセスが終了する
- 参加側で `Intercept hit` ログが出る
- 参加側で `Resolved room target` ログが出る
- 取得失敗時に `Fallback to original target` が出て通常接続継続

## 8. 最小切り分け手順

1. `run/config/nat_traversal_mod-common.toml` の共通キー（`room_name` / `relay_token`）確認
2. `run/config/nat_traversal_mod-server.toml` の host/relayキー確認
3. `run/config/nat_traversal_mod-client.toml` の client/relayキー確認
4. ホストログで `Room published` / `Relay host connector paired` を確認
5. 参加側ログで `Use local relay client connector` / `Resolved room target` を確認

## 9. 次フェーズ (推奨順)

1. M4の判定ラベルに基づく実測結果の蓄積
2. 友人向け運用手順の固定（README最小手順化）
3. シンプル中継サーバーソフト構築（`relay-server/`）
4. 低コスト短期キャッシュ（必要時）
5. ホスト側更新戦略の高度化（必要時）
6. UDP hole punching + QUICブリッジの実験ブランチ化

