package com.example.benchmark.service.runner;

import com.example.benchmark.model.BenchmarkResult;
import com.example.benchmark.model.WorkloadType;
import com.example.benchmark.service.CpuMonitor;
import com.example.benchmark.service.LatencyTracker;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AbstractJdbcRunner implements DbBenchmarkRunner {

    protected static final String[] CATEGORIES = {
        "Electronics", "Books", "Clothing", "Food", "Sports",
        "Toys", "Home", "Garden", "Beauty", "Automotive"
    };

    private volatile HikariDataSource ds;
    private volatile JdbcTemplate jdbc;
    protected final CpuMonitor cpuMonitor = new CpuMonitor();

    // ── Abstract hooks for subclasses ─────────────────────────────────────────

    protected abstract String jdbcUrl();
    protected abstract String jdbcUsername();
    protected abstract String jdbcPassword();
    protected abstract String driverClass();
    protected abstract String tableName();
    protected abstract String createTableDdl();
    protected abstract String dropTableDdl();
    protected abstract long   queryStorageSizeBytes();

    // Write-Amplification: override in MyRocksRunner
    protected double computeWaf(long userBytesWritten) { return -1; }

    // ── Connection management ─────────────────────────────────────────────────

    protected JdbcTemplate jdbc() {
        if (jdbc == null) {
            synchronized (this) {
                if (jdbc == null) {
                    HikariConfig cfg = new HikariConfig();
                    cfg.setJdbcUrl(jdbcUrl());
                    cfg.setUsername(jdbcUsername());
                    cfg.setPassword(jdbcPassword());
                    cfg.setDriverClassName(driverClass());
                    cfg.setMaximumPoolSize(5);
                    cfg.setConnectionTimeout(5_000);
                    cfg.setPoolName(getEngine().name() + "-pool");
                    ds   = new HikariDataSource(cfg);
                    jdbc = new JdbcTemplate(ds);
                }
            }
        }
        return jdbc;
    }

    // ── DbBenchmarkRunner ─────────────────────────────────────────────────────

    @Override
    public boolean isAvailable() {
        try {
            jdbc().queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void setupSchema() {
        jdbc().execute(dropTableDdl());
        jdbc().execute(createTableDdl());
    }

    @Override
    public void teardown() {
        try { jdbc().execute(dropTableDdl()); } catch (Exception ignored) {}
    }

    // ── INSERT ONLY ───────────────────────────────────────────────────────────

    @Override
    public BenchmarkResult runInsertOnly(int rowCount) {
        LatencyTracker tracker = new LatencyTracker();
        long approxUserBytes   = (long) rowCount * 80;  // ~80 bytes per row
        double[] wafHolder     = {-1};

        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < rowCount; i++) {
                long t = System.nanoTime();
                jdbc().update(insertSql(),
                    (long) i + 1,
                    "Product-" + (i + 1),
                    randPrice(rng),
                    CATEGORIES[rng.nextInt(CATEGORIES.length)]);
                tracker.record(System.nanoTime() - t);
            }
            wafHolder[0] = computeWaf(approxUserBytes);
        });

        return build(WorkloadType.INSERT_ONLY, rowCount, tracker, cpu, wafHolder[0], -1);
    }

    // ── UPDATE HEAVY ─────────────────────────────────────────────────────────

    @Override
    public BenchmarkResult runUpdateHeavy(int rowCount) {
        LatencyTracker tracker = new LatencyTracker();
        long approxUserBytes   = (long) rowCount * 20;
        double[] wafHolder     = {-1};

        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < rowCount; i++) {
                long id = (long) rng.nextInt(rowCount) + 1;
                long t  = System.nanoTime();
                jdbc().update(
                    "UPDATE " + tableName() + " SET price = ?, category = ? WHERE id = ?",
                    randPrice(rng),
                    CATEGORIES[rng.nextInt(CATEGORIES.length)],
                    id);
                tracker.record(System.nanoTime() - t);
            }
            wafHolder[0] = computeWaf(approxUserBytes);
        });

        return build(WorkloadType.UPDATE_HEAVY, rowCount, tracker, cpu, wafHolder[0], -1);
    }

    // ── POINT READ ────────────────────────────────────────────────────────────

    @Override
    public BenchmarkResult runPointRead(int iterations) {
        long total = countRows();
        if (total == 0) return BenchmarkResult.error(getEngine(), WorkloadType.POINT_READ, "データがありません");

        LatencyTracker tracker = new LatencyTracker();
        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < iterations; i++) {
                long id = (long) rng.nextInt((int) Math.min(total, Integer.MAX_VALUE)) + 1;
                long t  = System.nanoTime();
                jdbc().queryForList("SELECT * FROM " + tableName() + " WHERE id = ?", id);
                tracker.record(System.nanoTime() - t);
            }
        });

        return build(WorkloadType.POINT_READ, iterations, tracker, cpu, -1, -1);
    }

    // ── RANGE READ ────────────────────────────────────────────────────────────

    @Override
    public BenchmarkResult runRangeRead(int iterations) {
        if (countRows() == 0) return BenchmarkResult.error(getEngine(), WorkloadType.RANGE_READ, "データがありません");

        LatencyTracker tracker = new LatencyTracker();
        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < iterations; i++) {
                double base = rng.nextDouble() * 900;
                BigDecimal lo = BigDecimal.valueOf(base).setScale(2, RoundingMode.HALF_UP);
                BigDecimal hi = lo.add(BigDecimal.valueOf(100));
                long t = System.nanoTime();
                jdbc().queryForList(
                    "SELECT * FROM " + tableName() + " WHERE price BETWEEN ? AND ? LIMIT 200",
                    lo, hi);
                tracker.record(System.nanoTime() - t);
            }
        });

        return build(WorkloadType.RANGE_READ, iterations, tracker, cpu, -1, -1);
    }

    // ── DELETE HEAVY ──────────────────────────────────────────────────────────

    @Override
    public BenchmarkResult runDeleteHeavy(int rowCount) {
        if (countRows() == 0) return BenchmarkResult.error(getEngine(), WorkloadType.DELETE_HEAVY, "データがありません");

        LatencyTracker tracker = new LatencyTracker();
        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < rowCount; i++) {
                long id = (long) rng.nextInt(rowCount * 2) + 1;
                long t  = System.nanoTime();
                jdbc().update("DELETE FROM " + tableName() + " WHERE id = ?", id);
                tracker.record(System.nanoTime() - t);
            }
        });

        return build(WorkloadType.DELETE_HEAVY, rowCount, tracker, cpu, -1, -1);
    }

    // ── MIXED (70% read / 30% write) ─────────────────────────────────────────

    @Override
    public BenchmarkResult runMixed(int totalOps) {
        long total = countRows();
        if (total == 0) return BenchmarkResult.error(getEngine(), WorkloadType.MIXED, "データがありません");

        LatencyTracker tracker = new LatencyTracker();
        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < totalOps; i++) {
                long t = System.nanoTime();
                if (rng.nextInt(10) < 7) {
                    long id = (long) rng.nextInt((int) Math.min(total, Integer.MAX_VALUE)) + 1;
                    jdbc().queryForList("SELECT * FROM " + tableName() + " WHERE id = ?", id);
                } else {
                    long id = (long) rng.nextInt((int) Math.min(total, Integer.MAX_VALUE)) + 1;
                    jdbc().update(
                        "UPDATE " + tableName() + " SET price = ? WHERE id = ?",
                        randPrice(rng), id);
                }
                tracker.record(System.nanoTime() - t);
            }
        });

        return build(WorkloadType.MIXED, totalOps, tracker, cpu, -1, -1);
    }

    // ── STORAGE SIZE ──────────────────────────────────────────────────────────

    @Override
    public BenchmarkResult getStorageSize() {
        try {
            long bytes = queryStorageSizeBytes();
            return build(WorkloadType.STORAGE_SIZE, 0,
                new LatencyTracker(), 0, -1, bytes);
        } catch (Exception e) {
            return BenchmarkResult.error(getEngine(), WorkloadType.STORAGE_SIZE, e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    protected long countRows() {
        Long n = jdbc().queryForObject("SELECT COUNT(*) FROM " + tableName(), Long.class);
        return n == null ? 0 : n;
    }

    protected String insertSql() {
        return "INSERT INTO " + tableName() + " (id, name, price, category) VALUES (?,?,?,?)";
    }

    private BigDecimal randPrice(Random rng) {
        return BigDecimal.valueOf(rng.nextDouble() * 999 + 1).setScale(2, RoundingMode.HALF_UP);
    }

    private BenchmarkResult build(WorkloadType wl, int opCount, LatencyTracker t,
                                   double cpu, double waf, long storageBytes) {
        return new BenchmarkResult(
            getEngine(), wl, opCount,
            round2(t.throughputOpsPerSec()),
            round2(t.avgMs()),
            round2(t.p95Ms()),
            round2(t.p99Ms()),
            round2(cpu),
            waf < 0 ? -1 : round2(waf),
            storageBytes,
            "success", ""
        );
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
