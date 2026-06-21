# Benchmark Plotter

`outputs/benchmark-results.csv` から、DB Engine / Workload / Index Type の比較グラフをPNGで生成します。

## Setup

```bash
python3 -m pip install -r plots/requirements.txt
```

## Usage

```bash
python3 plots/plot_benchmark_results.py
```

デフォルトでは `plots/out` に以下のようなグラフを出力します。

- ワークロード別のDB Engine比較: throughput / latency / CPU
- Workloadごとのスループット推移
- Index Type別の平均スループット比較
- P99 latency heatmap
- Storage size比較
- WAF比較

任意のCSVや出力先を指定する場合:

```bash
python3 plots/plot_benchmark_results.py \
  --input outputs/benchmark-results.csv \
  --output-dir plots/out
```

組み込みRocksDBを除外して、外部DBサーバー系だけで比較する場合:

```bash
python3 plots/plot_benchmark_results.py \
  --exclude-embedded-rocksdb \
  --output-dir plots/out-server-db
```

任意のDB Engineを除外する場合:

```bash
python3 plots/plot_benchmark_results.py \
  --exclude-engine RocksDB \
  --exclude-engine "MySQL MyRocks"
```
