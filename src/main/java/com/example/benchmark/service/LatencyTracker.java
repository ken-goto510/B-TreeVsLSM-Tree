package com.example.benchmark.service;

import java.util.ArrayList;
import java.util.List;

public class LatencyTracker {

    private final List<Long> nanos = new ArrayList<>();
    private long startMs;

    public void start() {
        startMs = System.currentTimeMillis();
    }

    public void record(long elapsedNanos) {
        nanos.add(elapsedNanos);
    }

    public long elapsedMs() {
        return System.currentTimeMillis() - startMs;
    }

    public int count() {
        return nanos.size();
    }

    public double avgMs() {
        if (nanos.isEmpty()) return 0;
        return nanos.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
    }

    public double p95Ms() { return percentileMs(95); }
    public double p99Ms() { return percentileMs(99); }

    private double percentileMs(int p) {
        if (nanos.isEmpty()) return 0;
        long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))] / 1_000_000.0;
    }

    public double throughputOpsPerSec() {
        long elapsed = elapsedMs();
        return elapsed == 0 ? 0 : nanos.size() * 1000.0 / elapsed;
    }

    public void reset() {
        nanos.clear();
    }
}
