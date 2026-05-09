# MySQL Index Benchmark: B-tree vs SSTable

MySQLの2種類のインデックス実装（InnoDB B-tree / MyRocks SSTable）の性能を実測比較するSpring Bootアプリです。

## 概要

| エンジン | インデックス構造 | 特徴 |
|----------|----------------|------|
| **InnoDB** | B-tree（平衡木） | 読み込み重視。クラスタードインデックスで範囲検索が速い |
| **MyRocks** | SSTable / LSM-tree | 書き込み重視。MemTableへの追記で書込みアンプリフィケーションが低い |

## 必要な環境

- Java 17+
- Maven（またはIntelliJ内蔵Maven）
- Docker Desktop

## 起動手順

```bash
# 1. Percona Server（MyRocks対応MySQL）をDockerで起動
docker compose up percona -d

# 2. ビルド
mvn package -DskipTests

# 3. アプリ起動
java -jar target/index-benchmark-0.0.1-SNAPSHOT.jar

# 4. ブラウザで開く
open http://localhost:8080
```

> ローカルにMySQLが3306で動いている場合、PerconaはポートKanako3307で起動します。

## アーキテクチャ

```
ブラウザ (http://localhost:8080)
    ↓ REST API
Spring Boot (BenchmarkController → BenchmarkService)
    ↓ JdbcTemplate（JPA不使用・計測精度のため）
Percona Server 8.0 (Docker, port 3307)
    ├── products_innodb   ENGINE=InnoDB   ← B-tree
    └── products_myrocks  ENGINE=ROCKSDB  ← SSTable
```

JPA ではなく `JdbcTemplate` を使用することで、1次キャッシュや遅延ロードがベンチマーク結果に混入しないようにしています。

## ベンチマーク内容

| 操作 | 説明 | 期待される勝者 |
|------|------|----------------|
| **Bulk INSERT** | N件を一括バッチ投入 | MyRocks（MemTable書き込みでB-treeのページ分割が不要） |
| **Point Lookup** | PKによるランダム点検索 | InnoDB（クラスタードB-treeで1回のツリー走査） |
| **Range Scan** | `price BETWEEN` 範囲検索 | InnoDB（リーフノードのリンクリストで連続読み出し） |
| **Secondary Index** | `category` セカンダリインデックス検索 | 環境依存 |
| **DELETE** | 全件削除 | 規模による（大量データではMyRocksのtombstone方式が有利） |

## REST API

```
# 全ベンチマーク一括実行
POST /api/benchmark/run-all?insertCount=10000

# 個別実行
POST   /api/benchmark/insert?count=10000
GET    /api/benchmark/point-lookup?iterations=1000
GET    /api/benchmark/range-scan?iterations=200
GET    /api/benchmark/secondary-index?iterations=200
DELETE /api/benchmark/delete

# ユーティリティ
POST /api/benchmark/reset
GET  /api/benchmark/status
```

## プロジェクト構成

```
├── docker-compose.yml              # Percona Server
├── mysql/
│   └── init/01-schema.sql          # テーブル定義（InnoDB / ROCKSDB）
└── src/main/java/com/example/benchmark/
    ├── BenchmarkApplication.java
    ├── controller/BenchmarkController.java
    ├── service/BenchmarkService.java   # 計測ロジック
    └── model/BenchmarkResult.java
```

## インデックス構造の補足

### B-tree（InnoDB）

- データはPKの順序でリーフノードに格納（クラスタードインデックス）
- リーフノードは双方向リンクリストで繋がっており範囲スキャンが効率的
- 書き込み時にページを直接更新（Write-in-place）

### LSM-tree / SSTable（MyRocks）

- 書き込みはまずメモリ上のMemTableに積む → しきい値でSSTfile（不変ファイル）にflush
- SSTfileはImmutable（書き換えない）のでシーケンシャル書き込みのみ発生
- 読み込み時は MemTable + 複数レベルのSSTableを確認（読み込みアンプリフィケーション）
- バックグラウンドCompactionで古いSSTableをマージし読み込み効率を維持