package com.example.benchmark.model;

public record BenchmarkResult(
        String operation,
        String description,
        long innodbMs,
        long myrocksMs,
        int rowCount,
        double speedupRatio,
        String winner
) {
    public static BenchmarkResult of(String op, String desc, long innodbMs, long myrocksMs, int rowCount) {
        double ratio = myrocksMs == 0 ? 0 : (double) innodbMs / myrocksMs;
        String winner;
        if (innodbMs < myrocksMs) {
            winner = "InnoDB (B-tree)";
        } else if (myrocksMs < innodbMs) {
            winner = "MyRocks (SSTable)";
        } else {
            winner = "Tie";
        }
        return new BenchmarkResult(op, desc, innodbMs, myrocksMs, rowCount,
                Math.round(ratio * 100.0) / 100.0, winner);
    }
}