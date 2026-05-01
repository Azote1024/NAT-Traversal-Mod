# マイルストーン再定義 (2026-05-01)

## 1. 全体ロードマップ

- Phase A: STUN前安定運用（完了）
- Phase B: STUN導入準備（完了）
- Phase C: STUN最小実装（完了）
- Phase D: LAN外安定化（進行中）
- Phase E: 友人向け運用固定化（次）
- Phase F: シンプル中継サーバーソフト構築（予定）
- Phase G: UDP hole punching + QUICブリッジ（実験ブランチ）

## 2. 直近マイルストーン

### M2: STUN導入準備（進行中）

- 目的:
  - 既存MVP挙動を壊さず、STUN実装を差し込める構造にする。
- 完了条件:
  1. `rooms` 契約が docs で固定されている
  2. `stun_*` 設定が存在し、`false` で現行挙動を維持
  3. publisher 側に候補フィールド差し込みポイントがある
- 成果物:
  - `docs/rooms-data-contract.md`
  - `neoforge/src/main/java/com/azote/nat_traversal_mod/Config.java`
  - `neoforge/src/main/java/com/azote/nat_traversal_mod/net/SupabaseRoomsPublisher.java`

### M3: STUN最小実装（完了）

- 目的:
  - STUN問い合わせを行い、得られた候補を `rooms` へ保存できる最小構成を作る。
- 実装順:
  1. Supabase migration で STUN拡張カラムを追加 (着手済み)
  2. STUN client（最小）実装
  3. `stun_enabled=true` 時の publish payload 拡張
  4. クライアント側解決優先順位を追加
- 完了条件:
  - `stun_enabled=false` で既存動作を維持
  - `stun_enabled=true` で STUN候補を publish 可能

進捗メモ:

- M3-1: `supabase/migrations/20260501061000_add_stun_candidate_columns.sql` を追加
- M3-2: `neoforge/src/main/java/com/azote/nat_traversal_mod/net/StunClient.java` を追加
- M3-2: `SupabaseRoomsPublisher` で `stun_enabled=true` 時に `nat_method/public_endpoint/candidates` を送信
- M3-3: `SupabaseRoomsClient` で `public_endpoint` 優先の解決順を追加

### M4: LAN外安定化（次）

- 目的:
  - LAN外試験で成功/失敗の切り分けを再現可能にし、運用判断を安定化する。
- 必須タスク:
  1. `docs/lan-outside-test-plan.md` の判定基準を Gate 3 と一致させる
  2. STUN失敗時の direct 降格ログを維持する
  3. `public_endpoint` 未採用時の理由ログを確認可能にする
- 完了条件:
  - 同条件で同じ判定ラベル（成功/失敗原因）を再現できる
  - ログのみで「到達性問題」「データ不整合」「鮮度問題」を区別できる

進捗メモ:

- `SUCCESS_PUBLIC_ENDPOINT` を確認
- STUN無効ホスト時の direct 降格を確認

### M5: 友人向け運用固定化（次）

- 目的:
  - 配布向け設定と手順を最小化し、運用ミスを減らす。
- 必須タスク:
  1. READMEの手順を「最小3項目中心」に整理
  2. 判定ラベルの運用をテンプレート化
  3. STUN失敗時ログを簡潔化（Unresolved addressのノイズ抑制）
- 完了条件:
  - 友人向けセットアップ手順だけで再現可能
  - 失敗時に判定ラベルへ即分類できる

### M6: シンプル中継サーバーソフト構築（予定）

- 目的:
  - 到達可能な場所に置く自前中継で、ポート開放不可環境の成功率を上げる。
- ワークスペース:
  - プロジェクトルートに `relay-server/` を追加して実装する。
- 必須タスク:
  1. 中継サーバー最小仕様の固定（TCPバイト中継）
  2. `rooms` への relay情報保存（`relay_endpoint` / `relay_token` / `relay_status`）
  3. クライアント解決順に relay経路を追加
- 完了条件:
  - `relay_status=ready` のルームで relay経路接続が確認できる
  - 既存 direct/stun 経路との後方互換を維持

進行メモ（動くもの優先）:

- `relay-server/relay_server.py` 実装済み
- `relay-server/start.py` + `relay_config.toml` 実装済み
- `relay-server/test_relay.py` スモークテスト成功
- Ubuntu relayサーバーのUFW調整後、`Test-NetConnection <relay_connect_endpoint>:40000` 成功

直近のMUST:

1. `SupabaseRoomsPublisher` で `relay_endpoint` / `relay_token` / `relay_status` を publish
2. `SupabaseRoomsClient` の relay経路をE2Eで検証
3. READMEに relay利用の最小手順を追加

後回しTODO:

1. `relay_token` のTTL失効と古いセッション掃除
2. relayログのローテーション/簡易メトリクス
3. 中継接続数の上限とレート制御

## 3. リスク管理

- データ契約の不整合:
  - 対策: `docs/rooms-data-contract.md` を唯一の基準にする
- LAN外の到達性不安定:
  - 対策: `docs/lan-outside-test-plan.md` の判定ログで切り分け
- 設定ミス:
  - 対策: `publish_host_ip` 未設定時は warn とフォールバックを維持

## 4. テストゲート

- Gate 1 (M2完了判定):
  - `compileJava` 成功
  - 既存接続（`play.mc.local`）成功
- Gate 2 (M3完了判定):
  - STUN有効/無効の両パスが壊れていない
  - `rooms` で候補フィールド更新が確認できる
- Gate 3 (M4完了判定):
  - LAN外テストで再現性ある結果が取得できる
  - ログで失敗原因を分類できる

