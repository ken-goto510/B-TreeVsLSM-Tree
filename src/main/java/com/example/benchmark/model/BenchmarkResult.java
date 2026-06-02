package com.example.benchmark.model;

public record BenchmarkResult(
        DbEngine    dbEngine,
        WorkloadType workloadType,
        int          operationCount,
        double       throughputOpsPerSec,
        double       avgLatencyMs,
        double       p95LatencyMs,
        double       p99LatencyMs,
        double       cpuUsagePercent,
        double       writeAmplificationFactor,
        long         storageSizeBytes,
        String       status,
        String       notes
) {
    public static BenchmarkResult unavailable(DbEngine engine, WorkloadType workload) {
        return new BenchmarkResult(engine, workload, 0, 0, 0, 0, 0, 0, -1, -1,
                "unavailable", "DBに接続できません");
    }

    public static BenchmarkResult error(DbEngine engine, WorkloadType workload, String msg) {
        return new BenchmarkResult(engine, workload, 0, 0, 0, 0, 0, 0, -1, -1,
                "error", msg);
    }
}
