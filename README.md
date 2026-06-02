# DB Engine Benchmark: B-tree vs LSM-tree

7種類のDBエンジンを対象に、B-tree / LSM-tree の性能差をワークロード別・指標別に比較計測する Spring Boot アプリです。

## 比較対象

| エンジン | インデックス構造 | 動作形態 |
|---|---|---|
| **PostgreSQL** | B-tree | 独立コンテナ (port 5432) |
| **MySQL InnoDB** | B-tree | Percona コンテナ (port 3307) |
| **MyRocks** | LSM-tree (RocksDB) | Percona コンテナ (port 3307) |
| **Apache Cassandra** | LSM-tree | 独立コンテナ (port 9042) |
| **ScyllaDB** | LSM-tree (C++再実装) | 独立コンテナ (port 9043) |
| **RocksDB** | LSM-tree | アプリ組み込み (embedded) |
| **CockroachDB** | B-tree (分散SQL) | 独立コンテナ (port 26257) |

## 計測指標

| # | 指標 | 説明 |
|---|---|---|
| ① | **スループット** (ops/s) | 単位時間あたりの処理件数 |
| ② | **平均レイテンシ** (ms) | 1オペレーションあたりの平均応答時間 |
| ③ | **P95 / P99 レイテンシ** (ms) | 95/99パーセンタイルの遅延分布 |
| ④ | **CPU 使用率** (%) | ベンチマーク中のプロセス CPU 負荷 |
| ⑤ | **WAF** (書き込み増幅率) | ユーザーデータに対する実ディスク書込み倍率。RocksDB/MyRocks のみ計測 |

## ワークロード

| ワークロード | 内容 |
|---|---|
| **Insert Only** | 連続インサート（シーケンシャル書き込み） |
| **Update Heavy** | 既存キーを繰り返しランダム更新 |
| **Point Read** | 主キーによる1件検索 |
| **Range Read** | price 範囲スキャン (BETWEEN) |
| **Delete Heavy** | ランダムキー削除 |
| **Mixed Workload** | Read 70% / Write 30% 混合 |
| **Storage Size** | 各フェーズ後のデータ領域サイズ計測 |

## 必要環境

- Java 17+
- Maven 3.6+
- Docker Desktop

## 起動手順

```bash
# 1. JAR ビルド
mvn package -DskipTests

# 2. 全コンテナ起動（初回は Cassandra/ScyllaDB の起動に 2〜3 分かかります）
docker compose up --build

# 3. ブラウザで開く
open http://localhost:8080
```

> **注意**: Cassandra / ScyllaDB は起動完了まで `nodetool status` が `UN` を返すまで待機します（`start_period: 90s`）。それより前にベンチマークを実行すると "接続不可" と表示されます。

## アーキテクチャ

```
ブラウザ (http://localhost:8080)
    ↓ REST API
BenchmarkController
    ↓
BenchmarkOrchestrator  ─── ① 利用可能なエンジン確認
    │                  ─── ② setupSchema → 各ワークロード実行 → teardown
    ├── PostgresRunner        (JDBC / PostgreSQL driver)
    ├── MySqlInnodbRunner     (JDBC / MySQL driver)
    ├── MyRocksRunner         (JDBC / MySQL driver + ENGINE=ROCKSDB)
    ├── CockroachDbRunner     (JDBC / PostgreSQL driver)
    ├── CassandraRunner       (DataStax Java Driver CQL)
    ├── ScyllaDbRunner        (DataStax Java Driver CQL)
    └── RocksDbRunner         (rocksdbjni 組み込み)

計測インフラ
    ├── LatencyTracker    全オペレーションのナノ秒レイテンシを収集 → avg/P95/P99 算出
    └── CpuMonitor        ベンチマーク中の JVM プロセス CPU 使用率をサンプリング
```

JPA ではなく素の JDBC / ドライバ API を使用し、1次キャッシュや遅延ロードがベンチマーク結果に混入しないようにしています。

## REST API

```
# 利用可能なエンジン一覧
GET  /api/benchmark/engines

# ベンチマーク実行
POST /api/benchmark/run?rowCount=5000&readIter=1000
     &engines=POSTGRESQL&engines=MYSQL_INNODB  ← 省略で全エンジン

# 最後の結果を取得
GET  /api/benchmark/results

# CSV ダウンロード
GET  /api/benchmark/results/csv

# 実行状態確認
GET  /api/benchmark/status
```

## プロジェクト構成

```
├── docker-compose.yml
├── Dockerfile                              # eclipse-temurin:21-jre-jammy (Ubuntu/glibc)
├── mysql/
│   └── conf.d/myrocks.cnf                 # RocksDB プラグイン設定
└── src/main/java/com/example/benchmark/
    ├── BenchmarkApplication.java
    ├── model/
    │   ├── DbEngine.java                  # エンジン enum (7種)
    │   ├── WorkloadType.java              # ワークロード enum
    │   ├── BenchmarkResult.java           # 計測結果 record
    │   └── BenchmarkProperties.java       # application.yml バインド
    ├── service/
    │   ├── LatencyTracker.java            # P95/P99 算出
    │   ├── CpuMonitor.java                # CPU サンプリング
    │   ├── BenchmarkOrchestrator.java     # 全体制御
    │   └── runner/
    │       ├── DbBenchmarkRunner.java     # インターフェース
    │       ├── AbstractJdbcRunner.java    # JDBC 共通実装
    │       ├── PostgresRunner.java
    │       ├── MySqlInnodbRunner.java
    │       ├── MyRocksRunner.java
    │       ├── CockroachDbRunner.java
    │       ├── AbstractCassandraRunner.java
    │       ├── CassandraRunner.java
    │       ├── ScyllaDbRunner.java
    │       └── RocksDbRunner.java
    └── controller/
        └── BenchmarkController.java       # REST + CSV エクスポート
```

## インデックス構造の補足

### B-tree（PostgreSQL / InnoDB / CockroachDB）

- データは主キー順にリーフノードへ格納（クラスタードインデックス）
- リーフノードは双方向リンクリストで繋がり範囲スキャンが効率的
- 書き込みはページを直接更新（Write-in-place）→ 書き込み増幅が発生しやすい
- 読み込み性能が安定しており、OLTP 向きワークロードに強い

### LSM-tree（MyRocks / Cassandra / ScyllaDB / RocksDB）

- 書き込みはまずメモリ (MemTable) に積む → しきい値で不変 SSTable ファイルにフラッシュ
- SSTable は Immutable なのでシーケンシャル書き込みのみ → 書き込みアンプリフィケーションが低い
- 読み込み時は MemTable + 複数レベルの SSTable を走査（読み込み増幅）
- バックグラウンド Compaction で古い SSTable をマージし読み込み効率を維持
- 削除はまず tombstone の書き込み → Compaction 時に実際に消去

## 環境変数（docker-compose.yml）

| 変数 | デフォルト | 説明 |
|---|---|---|
| `BENCHMARK_MYSQL_URL` | `jdbc:mysql://percona:3306/benchmark_db` | MySQL 接続先 |
| `BENCHMARK_PG_URL` | `jdbc:postgresql://postgres:5432/benchmark_db` | PostgreSQL 接続先 |
| `BENCHMARK_CRDB_URL` | `jdbc:postgresql://cockroachdb:26257/defaultdb` | CockroachDB 接続先 |
| `BENCHMARK_CASSANDRA_HOSTS` | `cassandra` | Cassandra ホスト |
| `BENCHMARK_SCYLLA_HOSTS` | `scylladb` | ScyllaDB ホスト |
| `BENCHMARK_ROCKSDB_PATH` | `/tmp/rocksdb-benchmark` | 組み込み RocksDB データパス |
