package com.example.benchmark.service.runner;

import com.example.benchmark.model.BenchmarkProperties;
import com.example.benchmark.model.BenchmarkResult;
import com.example.benchmark.model.DbEngine;
import com.example.benchmark.model.WorkloadType;
import com.example.benchmark.service.CpuMonitor;
import com.example.benchmark.service.LatencyTracker;
import org.rocksdb.*;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RocksDbRunner implements DbBenchmarkRunner {

    private static final String[] CATEGORIES = {
        "Electronics", "Books", "Clothing", "Food", "Sports",
        "Toys", "Home", "Garden", "Beauty", "Automotive"
    };

    private static final boolean LIBRARY_AVAILABLE;

    static {
        boolean ok = false;
        try {
            RocksDB.loadLibrary();
            ok = true;
        } catch (Throwable t) {
            System.err.println("[RocksDbRunner] Native library unavailable: " + t.getMessage()
                + " — RocksDB workloads will be skipped.");
        }
        LIBRARY_AVAILABLE = ok;
    }

    private final String dbPath;
    private final CpuMonitor cpuMonitor = new CpuMonitor();
    private RocksDB db;
    private Statistics stats;

    public RocksDbRunner(BenchmarkProperties props) {
        this.dbPath = props.getRocksdb().getPath();
    }

    @Override
    public DbEngine getEngine() { return DbEngine.ROCKSDB; }

    @Override
    public boolean isAvailable() {
        if (!LIBRARY_AVAILABLE) return false;
        try {
            setupSchema();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void setupSchema() {
        if (!LIBRARY_AVAILABLE) throw new IllegalStateException("RocksDB native library not available");
        close();
        deleteDir(new File(dbPath));
        try {
            stats = new Statistics();
            Options opts = new Options()
                .setCreateIfMissing(true)
                .setStatistics(stats)
                .setWriteBufferSize(64 * 1024 * 1024L)
                .setMaxWriteBufferNumber(3);
            db = RocksDB.open(opts, dbPath);
        } catch (RocksDBException e) {
            throw new RuntimeException("RocksDB open failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void teardown() {
        close();
        deleteDir(new File(dbPath));
    }

    @Override
    public BenchmarkResult runInsertOnly(int rowCount) {
        LatencyTracker tracker = new LatencyTracker();
        long userBytes = (long) rowCount * 80;
        double[] wafHolder = {-1};

        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < rowCount; i++) {
                byte[] key = longToBytes((long) i + 1);
                byte[] val = encodeValue(
                    "Product-" + (i + 1),
                    randPrice(rng),
                    CATEGORIES[rng.nextInt(CATEGORIES.length)]);
                long t = System.nanoTime();
                try { db.put(key, val); } catch (RocksDBException e) { /* ignore */ }
                tracker.record(System.nanoTime() - t);
            }
            wafHolder[0] = computeWaf(userBytes);
        });
        return build(WorkloadType.INSERT_ONLY, rowCount, tracker, cpu, wafHolder[0], -1);
    }

    @Override
    public BenchmarkResult runUpdateHeavy(int rowCount) {
        LatencyTracker tracker = new LatencyTracker();
        long userBytes = (long) rowCount * 20;
        double[] wafHolder = {-1};

        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < rowCount; i++) {
                long id = (long) rng.nextInt(rowCount) + 1;
                byte[] key = longToBytes(id);
                byte[] val = encodeValue(
                    "Product-" + id, randPrice(rng),
                    CATEGORIES[rng.nextInt(CATEGORIES.length)]);
                long t = System.nanoTime();
                try { db.put(key, val); } catch (RocksDBException ignored) {}
                tracker.record(System.nanoTime() - t);
            }
            wafHolder[0] = computeWaf(userBytes);
        });
        return build(WorkloadType.UPDATE_HEAVY, rowCount, tracker, cpu, wafHolder[0], -1);
    }

    @Override
    public BenchmarkResult runPointRead(int iterations) {
        LatencyTracker tracker = new LatencyTracker();
        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < iterations; i++) {
                byte[] key = longToBytes((long) rng.nextInt(iterations) + 1);
                long t = System.nanoTime();
                try { db.get(key); } catch (RocksDBException ignored) {}
                tracker.record(System.nanoTime() - t);
            }
        });
        return build(WorkloadType.POINT_READ, iterations, tracker, cpu, -1, -1);
    }

    @Override
    public BenchmarkResult runRangeRead(int iterations) {
        LatencyTracker tracker = new LatencyTracker();
        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < iterations; i++) {
                long startId = (long) rng.nextInt(Math.max(1, iterations - 200)) + 1;
                long endId   = startId + 200;
                long t = System.nanoTime();
                try (RocksIterator it = db.newIterator()) {
                    int count = 0;
                    for (it.seek(longToBytes(startId));
                         it.isValid() && bytesToLong(it.key()) <= endId && count < 200;
                         it.next(), count++) {
                        it.value(); // materialize
                    }
                }
                tracker.record(System.nanoTime() - t);
            }
        });
        return build(WorkloadType.RANGE_READ, iterations, tracker, cpu, -1, -1);
    }

    @Override
    public BenchmarkResult runDeleteHeavy(int rowCount) {
        LatencyTracker tracker = new LatencyTracker();
        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < rowCount; i++) {
                byte[] key = longToBytes((long) rng.nextInt(rowCount * 2) + 1);
                long t = System.nanoTime();
                try { db.delete(key); } catch (RocksDBException ignored) {}
                tracker.record(System.nanoTime() - t);
            }
        });
        return build(WorkloadType.DELETE_HEAVY, rowCount, tracker, cpu, -1, -1);
    }

    @Override
    public BenchmarkResult runMixed(int totalOps) {
        LatencyTracker tracker = new LatencyTracker();
        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < totalOps; i++) {
                long id  = (long) rng.nextInt(totalOps) + 1;
                long t   = System.nanoTime();
                try {
                    if (rng.nextInt(10) < 7) {
                        db.get(longToBytes(id));
                    } else {
                        db.put(longToBytes(id),
                            encodeValue("Product-" + id, randPrice(rng),
                                CATEGORIES[rng.nextInt(CATEGORIES.length)]));
                    }
                } catch (RocksDBException ignored) {}
                tracker.record(System.nanoTime() - t);
            }
        });
        return build(WorkloadType.MIXED, totalOps, tracker, cpu, -1, -1);
    }

    @Override
    public BenchmarkResult getStorageSize() {
        try {
            long size = dirSize(Path.of(dbPath));
            return build(WorkloadType.STORAGE_SIZE, 0, new LatencyTracker(), 0, -1, size);
        } catch (Exception e) {
            return BenchmarkResult.error(getEngine(), WorkloadType.STORAGE_SIZE, e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double computeWaf(long userBytesWritten) {
        if (stats == null || userBytesWritten <= 0) return -1;
        long walBytes       = stats.getTickerCount(TickerType.WAL_FILE_BYTES);
        long compactBytes   = stats.getTickerCount(TickerType.COMPACT_WRITE_BYTES);
        long flushBytes     = stats.getTickerCount(TickerType.FLUSH_WRITE_BYTES);
        long totalDiskBytes = walBytes + compactBytes + flushBytes;
        return totalDiskBytes == 0 ? -1 :
               Math.round((double) totalDiskBytes / userBytesWritten * 100.0) / 100.0;
    }

    private byte[] longToBytes(long v) {
        return ByteBuffer.allocate(8).putLong(v).array();
    }

    private long bytesToLong(byte[] b) {
        return ByteBuffer.wrap(b).getLong();
    }

    private byte[] encodeValue(String name, BigDecimal price, String category) {
        return (name + "|" + price + "|" + category).getBytes(StandardCharsets.UTF_8);
    }

    private BigDecimal randPrice(Random rng) {
        return BigDecimal.valueOf(rng.nextDouble() * 999 + 1).setScale(2, RoundingMode.HALF_UP);
    }

    private void close() {
        if (db != null) { db.close(); db = null; }
        if (stats != null) { stats.close(); stats = null; }
    }

    private void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) Arrays.stream(files).forEach(f -> {
            if (f.isDirectory()) deleteDir(f); else f.delete();
        });
        dir.delete();
    }

    private long dirSize(Path dir) throws Exception {
        if (!Files.exists(dir)) return 0;
        return Files.walk(dir)
            .filter(Files::isRegularFile)
            .mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { return 0; } })
            .sum();
    }

    private BenchmarkResult build(WorkloadType wl, int opCount, LatencyTracker t,
                                   double cpu, double waf, long storageBytes) {
        return new BenchmarkResult(
            getEngine(), wl, opCount,
            round2(t.throughputOpsPerSec()),
            round2(t.avgMs()), round2(t.p95Ms()), round2(t.p99Ms()),
            round2(cpu),
            waf < 0 ? -1 : round2(waf),
            storageBytes, "success", ""
        );
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
