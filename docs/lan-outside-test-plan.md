# LAN外アクセス検証計画 (テザリング試験)

## 1. このModで現時点で完了している範囲

- `play.mc.local` への接続時に Supabase の `rooms` から `host_ip:host_port` を取得して差し替える。
- ホスト側は起動時/定期で `rooms` を更新し、停止時に `status=closed` にする。
- 取得失敗時は再試行せず、即座に元接続へフォールバックする。
- `updated_at` が 180 秒を超える古いデータは無効扱いでフォールバックする。
- `public_endpoint` が有効な場合は `host_ip:host_port` より優先して接続先に採用する。

## 2. まだ未完了の範囲 (重要)

- このModは「接続先解決とルーム管理」を担当する。
- TURN/本格P2P hole punching は未実装。
- relay経路はローカルrelayクライアントを前提にする。

## 3. テザリング試験は適切か

- 適切。LAN外からの実到達性を確認する最短手段。
- 特に「同一LANでは成功するが外から失敗する」ケースを切り分けやすい。

## 4. 試験構成

- Host: 自宅回線PC (このプロジェクトの `runServer`)
- Client: ノートPC + スマホテザリング回線 (このプロジェクトの `runClient`)
- 目的: `play.mc.local` で接続し、解決後に実際に参加できるか確認

## 5. 事前準備チェック

1. Hostの `nat_traversal_mod-common.toml`
   - `room_name` を固定
   - `publish_host_ip` は外部から到達可能なIPまたはDDNS
2. Clientの `nat_traversal_mod-common.toml`
   - 同じ `room_name`
   - `intercept_host=play.mc.local`
   - relay利用時は `relay_client_connector_enabled=true`
   - relay利用時はローカルrelayクライアントを起動
3. Supabase `rooms` 行が作成されること（`Room published` ログ）
4. Minecraftサーバーの実ポート設定確認（デフォルト 25565）

## 6. 試験手順

### Step A: ホスト側起動

- `runServer` 実行
- ログで `Room published` を確認

### Step B: クライアント側起動

- ノートPCをスマホテザリング接続
- `runClient` 実行
- `play.mc.local` で接続

### Step C: 判定

- 成功条件:
  - `Intercept hit`
  - `Resolved room target`
  - 実際にワールド参加できる
- 失敗条件:
  - `Resolved room target` は出るが接続失敗 -> ネットワーク到達性の問題
  - `Room resolve failed` -> Supabase設定/データ同期の問題

## 7. 結果記録テンプレート

- DateTime:
- Host network type:
- Client network type:
- `room_name`:
- `publish_host_ip`:
- Resolved target log:
- Join result: Success / Fail
- If fail, error message:
- Notes:

### 判定ラベル (M4運用用)

- `SUCCESS_PUBLIC_ENDPOINT` : `Use public_endpoint from room` で参加成功
- `SUCCESS_RELAY_ENDPOINT` : `Use local relay client connector` で参加成功
- `SUCCESS_HOST_FALLBACK` : `public_endpoint` 未採用でも `host_ip:host_port` で参加成功
- `FAIL_REACHABILITY` : `Resolved room target` 後に接続不可（到達性問題）
- `FAIL_STALE` : `Room data is stale` でフォールバック
- `FAIL_DATA_SHAPE` : `public_endpoint` 不正またはルームデータ不整合

## 8. 失敗時の切り分け

1. `Room resolve failed` が出る
   - config不一致、`room_name` ミス、Supabase更新失敗を確認
2. `Resolved room target` が出るのに参加不可
   - `host_ip:host_port` の外部到達性を確認
   - ルーター/NAT/CGNAT/ファイアウォール条件を確認
   - STUN有効時は `public_endpoint` の値と到達性を確認
3. `Room data is stale` が出る
   - ホスト側のpublishが継続しているか確認
   - Supabase上の `updated_at` が進んでいるか確認
4. 同一LANのみ成功しLAN外失敗
   - これは本Mod未実装領域（P2P中継なし）で想定内
5. relay host connector が `Connect timed out`
   - relayサーバー待受確認（`ss -ltnp | grep 40000`）
   - Ubuntuの場合は `ufw allow 40000/tcp` を確認
   - ホスト側で `Test-NetConnection <relay_connect_endpointのIP> -Port 40000` を確認

## 9. 次の判断基準

- LAN外試験で安定成功しない場合:
  - 今後は relay/TURN 相当の中継設計を別フェーズで検討
- LAN外試験で成功する場合:
  - 現設計を維持し、運用改善（通知、再試行、キャッシュ）を進める

