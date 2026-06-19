#!/usr/bin/env python3
"""Generate benchmark comparison charts from outputs/*.csv."""

from __future__ import annotations

import argparse
import csv
import math
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

try:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
except ImportError as exc:  # pragma: no cover - user-facing dependency message
    raise SystemExit(
        "matplotlib is required. Install it with: python3 -m pip install -r plots/requirements.txt"
    ) from exc


METRICS = {
    "throughput": ("Throughput(ops/s)", "Throughput (ops/s)", True),
    "avg_latency": ("Avg Latency(ms)", "Average Latency (ms)", False),
    "p95_latency": ("P95 Latency(ms)", "P95 Latency (ms)", False),
    "p99_latency": ("P99 Latency(ms)", "P99 Latency (ms)", False),
    "cpu": ("CPU(%)", "CPU (%)", False),
}

WORKLOAD_ORDER = [
    "Insert Only",
    "Update Heavy",
    "Point Read",
    "Range Read",
    "Delete Heavy",
    "Mixed Workload",
]

INDEX_COLORS = {
    "B-tree": "#2f6f9f",
    "LSM-tree": "#c16622",
}


@dataclass(frozen=True)
class BenchmarkRow:
    db_engine: str
    index_type: str
    workload: str
    status: str
    operations: int
    throughput: float
    avg_latency: float
    p95_latency: float
    p99_latency: float
    cpu: float
    waf: float | None
    storage_bytes: float | None

    def value(self, metric: str) -> float | None:
        value = getattr(self, metric)
        if value is None or math.isnan(value):
            return None
        return float(value)


def parse_number(raw: str) -> float | None:
    raw = (raw or "").strip()
    if not raw or raw.upper() == "N/A":
        return None
    return float(raw)


def parse_int(raw: str) -> int:
    value = parse_number(raw)
    return int(value or 0)


def read_rows(csv_paths: Iterable[Path]) -> list[BenchmarkRow]:
    rows: list[BenchmarkRow] = []
    for csv_path in csv_paths:
        with csv_path.open(newline="", encoding="utf-8-sig") as handle:
            reader = csv.DictReader(handle)
            for row in reader:
                rows.append(
                    BenchmarkRow(
                        db_engine=row["DB Engine"],
                        index_type=row["Index Type"],
                        workload=row["Workload"],
                        status=row["Status"],
                        operations=parse_int(row["Operations"]),
                        throughput=parse_number(row["Throughput(ops/s)"]) or 0.0,
                        avg_latency=parse_number(row["Avg Latency(ms)"]) or 0.0,
                        p95_latency=parse_number(row["P95 Latency(ms)"]) or 0.0,
                        p99_latency=parse_number(row["P99 Latency(ms)"]) or 0.0,
                        cpu=parse_number(row["CPU(%)"]) or 0.0,
                        waf=parse_number(row["WAF"]),
                        storage_bytes=parse_number(row["Storage(bytes)"]),
                    )
                )
    return rows


def ordered(values: Iterable[str], preferred: list[str] | None = None) -> list[str]:
    unique = list(dict.fromkeys(values))
    if preferred is None:
        return sorted(unique)
    ranked = [value for value in preferred if value in unique]
    rest = sorted(value for value in unique if value not in preferred)
    return ranked + rest


def safe_filename(name: str) -> str:
    return (
        name.lower()
        .replace(" ", "_")
        .replace("/", "_")
        .replace("(", "")
        .replace(")", "")
        .replace("%", "pct")
    )


def save_current_figure(output_dir: Path, filename: str) -> None:
    output_path = output_dir / filename
    plt.tight_layout()
    plt.savefig(output_path, dpi=180, bbox_inches="tight")
    plt.close()
    print(f"wrote {output_path}")


def add_value_labels(ax, values: list[float]) -> None:
    if not values:
        return
    max_value = max(values)
    if max_value <= 0:
        return
    offset = max_value * 0.015
    for patch, value in zip(ax.patches, values):
        if value <= 0:
            continue
        ax.text(
            patch.get_x() + patch.get_width() / 2,
            patch.get_height() + offset,
            f"{value:.0f}" if value >= 10 else f"{value:.2f}",
            ha="center",
            va="bottom",
            fontsize=7,
            rotation=90,
        )


def performance_rows(rows: Iterable[BenchmarkRow]) -> list[BenchmarkRow]:
    return [
        row
        for row in rows
        if row.status == "success" and row.workload != "Storage Size" and row.operations > 0
    ]


def latest_storage_rows(rows: Iterable[BenchmarkRow]) -> list[BenchmarkRow]:
    latest: dict[tuple[str, str], BenchmarkRow] = {}
    for row in rows:
        if row.status != "success" or row.workload != "Storage Size" or row.storage_bytes is None:
            continue
        latest[(row.db_engine, row.index_type)] = row
    return list(latest.values())


def plot_metric_by_workload(rows: list[BenchmarkRow], metric: str, output_dir: Path) -> None:
    _, label, higher_is_better = METRICS[metric]
    workloads = ordered((row.workload for row in rows), WORKLOAD_ORDER)
    engines = ordered(row.db_engine for row in rows)
    value_by_key = {(row.db_engine, row.workload): row.value(metric) for row in rows}

    for workload in workloads:
        values = [value_by_key.get((engine, workload), 0.0) or 0.0 for engine in engines]
        colors = [INDEX_COLORS.get(next((r.index_type for r in rows if r.db_engine == engine), ""), "#777777") for engine in engines]

        fig_width = max(8, len(engines) * 1.1)
        plt.figure(figsize=(fig_width, 5))
        ax = plt.gca()
        ax.bar(engines, values, color=colors)
        ax.set_title(f"{label} by DB Engine - {workload}")
        ax.set_ylabel(label)
        ax.set_xlabel("DB Engine")
        ax.tick_params(axis="x", rotation=35, labelsize=9)
        ax.grid(axis="y", alpha=0.25)
        if higher_is_better:
            ax.text(0.01, 0.97, "Higher is better", transform=ax.transAxes, va="top", fontsize=9)
        else:
            ax.text(0.01, 0.97, "Lower is better", transform=ax.transAxes, va="top", fontsize=9)
        add_value_labels(ax, values)
        save_current_figure(output_dir, f"{metric}_db_engine_{safe_filename(workload)}.png")


def plot_workload_profiles(rows: list[BenchmarkRow], output_dir: Path) -> None:
    engines = ordered(row.db_engine for row in rows)
    workloads = ordered((row.workload for row in rows), WORKLOAD_ORDER)
    value_by_key = {(row.db_engine, row.workload): row.throughput for row in rows}

    plt.figure(figsize=(11, 6))
    ax = plt.gca()
    for engine in engines:
        values = [value_by_key.get((engine, workload), 0.0) or 0.0 for workload in workloads]
        ax.plot(workloads, values, marker="o", linewidth=2, label=engine)

    ax.set_title("Throughput Profile by Workload")
    ax.set_ylabel("Throughput (ops/s)")
    ax.set_xlabel("Workload")
    ax.tick_params(axis="x", rotation=25, labelsize=9)
    ax.grid(axis="y", alpha=0.25)
    ax.legend(ncol=2, fontsize=8)
    save_current_figure(output_dir, "throughput_workload_profile.png")


def plot_index_type_comparison(rows: list[BenchmarkRow], output_dir: Path) -> None:
    workloads = ordered((row.workload for row in rows), WORKLOAD_ORDER)
    grouped: dict[tuple[str, str], list[float]] = defaultdict(list)
    for row in rows:
        grouped[(row.workload, row.index_type)].append(row.throughput)

    index_types = ordered(row.index_type for row in rows)
    x_positions = list(range(len(workloads)))
    width = 0.8 / max(1, len(index_types))

    plt.figure(figsize=(10, 5.5))
    ax = plt.gca()
    for idx, index_type in enumerate(index_types):
        values = [
            sum(grouped[(workload, index_type)]) / len(grouped[(workload, index_type)])
            if grouped[(workload, index_type)]
            else 0.0
            for workload in workloads
        ]
        xs = [x - 0.4 + width / 2 + idx * width for x in x_positions]
        ax.bar(xs, values, width=width, label=index_type, color=INDEX_COLORS.get(index_type, "#777777"))

    ax.set_title("Average Throughput by Index Type")
    ax.set_ylabel("Average Throughput (ops/s)")
    ax.set_xlabel("Workload")
    ax.set_xticks(x_positions)
    ax.set_xticklabels(workloads, rotation=25, ha="right")
    ax.grid(axis="y", alpha=0.25)
    ax.legend()
    save_current_figure(output_dir, "throughput_index_type_comparison.png")


def plot_latency_distribution(rows: list[BenchmarkRow], output_dir: Path) -> None:
    workloads = ordered((row.workload for row in rows), WORKLOAD_ORDER)
    engines = ordered(row.db_engine for row in rows)
    value_by_key = {(row.db_engine, row.workload): row.p99_latency for row in rows}

    matrix = [[value_by_key.get((engine, workload), math.nan) for workload in workloads] for engine in engines]

    plt.figure(figsize=(10, 5.5))
    ax = plt.gca()
    image = ax.imshow(matrix, aspect="auto", cmap="YlOrRd")
    ax.set_title("P99 Latency Heatmap")
    ax.set_xlabel("Workload")
    ax.set_ylabel("DB Engine")
    ax.set_xticks(range(len(workloads)))
    ax.set_xticklabels(workloads, rotation=30, ha="right")
    ax.set_yticks(range(len(engines)))
    ax.set_yticklabels(engines)
    for y, engine in enumerate(engines):
        for x, workload in enumerate(workloads):
            value = value_by_key.get((engine, workload))
            if value is not None:
                ax.text(x, y, f"{value:.2f}", ha="center", va="center", fontsize=7)
    plt.colorbar(image, ax=ax, label="P99 Latency (ms)")
    save_current_figure(output_dir, "p99_latency_heatmap.png")


def plot_storage_size(rows: list[BenchmarkRow], output_dir: Path) -> None:
    storage_rows = latest_storage_rows(rows)
    if not storage_rows:
        return

    storage_rows = sorted(storage_rows, key=lambda row: row.storage_bytes or 0, reverse=True)
    engines = [row.db_engine for row in storage_rows]
    values_mb = [(row.storage_bytes or 0.0) / (1024 * 1024) for row in storage_rows]
    colors = [INDEX_COLORS.get(row.index_type, "#777777") for row in storage_rows]

    plt.figure(figsize=(9, 5))
    ax = plt.gca()
    ax.bar(engines, values_mb, color=colors)
    ax.set_title("Latest Storage Size by DB Engine")
    ax.set_ylabel("Storage (MiB)")
    ax.set_xlabel("DB Engine")
    ax.tick_params(axis="x", rotation=35, labelsize=9)
    ax.grid(axis="y", alpha=0.25)
    add_value_labels(ax, values_mb)
    save_current_figure(output_dir, "storage_size_db_engine.png")


def plot_waf(rows: list[BenchmarkRow], output_dir: Path) -> None:
    waf_rows = [row for row in rows if row.status == "success" and row.waf is not None]
    if not waf_rows:
        return

    workloads = ordered((row.workload for row in waf_rows), WORKLOAD_ORDER)
    engines = ordered(row.db_engine for row in waf_rows)
    value_by_key = {(row.db_engine, row.workload): row.waf for row in waf_rows}

    plt.figure(figsize=(8, 5))
    ax = plt.gca()
    for engine in engines:
        values = [value_by_key.get((engine, workload), math.nan) for workload in workloads]
        ax.plot(workloads, values, marker="o", linewidth=2, label=engine)
    ax.set_title("Write Amplification Factor")
    ax.set_ylabel("WAF")
    ax.set_xlabel("Workload")
    ax.tick_params(axis="x", rotation=25, labelsize=9)
    ax.grid(axis="y", alpha=0.25)
    ax.legend()
    save_current_figure(output_dir, "waf_by_workload.png")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input",
        nargs="+",
        type=Path,
        default=[Path("outputs/benchmark-results.csv")],
        help="CSV file(s) to plot. Default: outputs/benchmark-results.csv",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("plots/out"),
        help="Directory for generated PNG files. Default: plots/out",
    )
    parser.add_argument(
        "--include-errors",
        action="store_true",
        help="Keep error rows in the loaded dataset. Performance charts still require successful operations.",
    )
    args = parser.parse_args()

    csv_paths = [path for path in args.input if path.exists()]
    missing = [str(path) for path in args.input if not path.exists()]
    if missing:
        raise SystemExit(f"CSV file not found: {', '.join(missing)}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    all_rows = read_rows(csv_paths)
    rows = all_rows if args.include_errors else [row for row in all_rows if row.status == "success"]
    perf_rows = performance_rows(rows)
    if not perf_rows:
        raise SystemExit("No successful performance rows found.")

    for metric in METRICS:
        plot_metric_by_workload(perf_rows, metric, args.output_dir)
    plot_workload_profiles(perf_rows, args.output_dir)
    plot_index_type_comparison(perf_rows, args.output_dir)
    plot_latency_distribution(perf_rows, args.output_dir)
    plot_storage_size(rows, args.output_dir)
    plot_waf(rows, args.output_dir)


if __name__ == "__main__":
    main()
