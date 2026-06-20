# ECS Fargate デプロイ手順

B-Tree vs LSM-Tree ベンチマークを **単一の Fargate タスク**（app + 5 DB コンテナ）で
動かすための手順。CPU 計測は ECS Task Metadata v4 経由で各 DB コンテナ単位に取得する
（`CpuMonitor` が `ECS_CONTAINER_METADATA_URI_V4` を自動検出）。

- アカウント: `074213351472` / リージョン: `us-east-1`（変える場合は各所を置換）
- タスク: 8 vCPU / 16 GB / ephemeral 40 GB
- 構成図・設計は `ecs/task-definition.json` と計画ファイル参照

---

## 前提
- `aws` CLI（ECR 権限）と `docker` が使えること
- ECS / IAM / EC2(VPC) はコンソールで操作（cli-user に ECS 権限が無いため）

---

## Step 1. イメージを ECR にミラー

リポジトリ root で：
```bash
bash ecs/mirror-images.sh
```
これで以下が ECR に入る（app と percona はビルド、他4 DB は Docker Hub からミラー）：
```
b-treevslsm-tree-app:latest
bench/percona:8.0   bench/postgres:16-alpine
bench/cockroach:v23.2.4   bench/cassandra:4.1   bench/scylla:5.4
```
確認：
```bash
aws ecr describe-images --region us-east-1 --repository-name b-treevslsm-tree-app \
  --query 'imageDetails[].imageTags' --output text
```

## Step 2. 実行ロール `ecsTaskExecutionRole`（無ければ作成）

ECR pull と CloudWatch Logs に必要。コンソール: IAM → ロール → 作成 →
信頼されたエンティティ「Elastic Container Service」→ ユースケース「Elastic Container Service Task」→
ポリシー `AmazonECSTaskExecutionRolePolicy` をアタッチ → 名前 `ecsTaskExecutionRole`。

> ログ: タスク定義は `awslogs-create-group: "true"` を指定しているが、
> `AmazonECSTaskExecutionRolePolicy` には `logs:CreateLogGroup` が**含まれない**。
> どちらかを実施：
> - (推奨) ロググループを事前作成: `aws logs create-log-group --log-group-name /ecs/db-benchmark --region us-east-1`
> - または実行ロールにインラインで `logs:CreateLogGroup` を追加

## Step 3. タスク定義を登録

コンソール: ECS → タスク定義 → 「新しいタスク定義を JSON で作成」→
`ecs/task-definition.json` の内容を貼り付け → 作成。

（CLI で行う場合・ECS 権限が必要）：
```bash
aws ecs register-task-definition --region us-east-1 \
  --cli-input-json file://ecs/task-definition.json
```

## Step 4. クラスター作成

コンソール: ECS → クラスター → クラスターの作成 → 名前 `db-benchmark` →
インフラストラクチャ「AWS Fargate」→ 作成。

## Step 5. セキュリティグループ

EC2 → セキュリティグループ → 作成（VPC はタスクを置くものと同じ）：
- インバウンド: `TCP 8080`（ソースは**自分の IP 推奨**）
- アウトバウンド: 全許可（ECR/イメージ取得・パッケージ取得に必要）

## Step 6. タスクを起動（Fargate・パブリック IP 付き）

コンソール: クラスター `db-benchmark` → タスク → 新しいタスクの実行：
- 起動タイプ: FARGATE
- タスク定義: `db-benchmark`（最新リビジョン）
- ネットワーキング:
  - VPC: デフォルト（または任意）
  - サブネット: **パブリックサブネット**
  - セキュリティグループ: Step 5 のもの
  - **パブリック IP: 有効（ENABLED）** ← これが無いと ECR から pull できず外部アクセスも不可
- 実行

> サービス（常時稼働・自動復旧）にしたい場合は「サービスの作成」を選ぶ。
> ベンチは使い捨てなので**単発タスク（Run task）で十分**、終わったら停止してコスト抑制。

## Step 7. 起動待ち & アクセス

- タスクの「ステータス」が RUNNING、各コンテナが healthy になるまで待つ
  （Cassandra/ScyllaDB は 1〜2 分）
- タスク詳細 → 「ネットワーキング」→ **パブリック IP** を確認
- ブラウザ: `http://<パブリックIP>:8080`

## Step 8. 動作確認

```bash
curl http://<パブリックIP>:8080/api/benchmark/status      # {"running":false}
curl http://<パブリックIP>:8080/api/benchmark/engines      # 7エンジン available

# Cassandra ベンチ（CPUがコンテナ単位で取れているか）
curl -X POST "http://<パブリックIP>:8080/api/benchmark/run?rowCount=3000&readIter=800&engines=CASSANDRA"
```
`cpuUsagePercent` が妥当値（> 0、マルチコアで 100% 超もありうる）なら、
**Task Metadata 方式で DB コンテナの CPU が取れている**＝移行成功。

CloudWatch Logs `/ecs/db-benchmark`（ストリーム接頭辞 cassandra/scylla/app …）で各起動ログを確認できる。

---

## ⚠ ScyllaDB の scylla-jmx ポート衝突（初回のみ確認）

ScyllaDB 同梱の `scylla-jmx` は 7199 を掴むため、Cassandra の JMX(7199) と衝突しうる。
- ヘルスチェックは JMX を使わず `cqlsh`（9043）で行うので、**healthy 判定には影響しない**
- ただし scylla-jmx の bind 失敗がコンテナを落とす場合は、CloudWatch の scylla ログを確認し、
  対処（scylla-jmx の無効化 or ポート変更）をタスク定義 command に追加する

ScyllaDB が `unhealthy` のまま、または再起動を繰り返す場合は scylla ログを最初に確認すること。

## コストの注意
8 vCPU / 16 GB の Fargate は時間課金が高い。**ベンチ終了後はタスクを停止**すること
（サービス化した場合は希望数を 0 に）。
