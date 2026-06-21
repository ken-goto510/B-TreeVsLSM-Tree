package com.example.benchmark.service.runner;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.example.benchmark.model.BenchmarkResult;
import com.example.benchmark.model.WorkloadType;
import com.example.benchmark.service.CpuMonitor;
import com.example.benchmark.service.LatencyTracker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public abstract class AbstractCassandraRunner implements DbBenchmarkRunner {

    protected static final String[] CATEGORIES = {
        "Electronics", "Books", "Clothing", "Food", "Sports",
        "Toys", "Home", "Garden", "Beauty", "Automotive"
    };
    protected static final String KEYSPACE = "benchmark";
    protected static final String TABLE    = "products";

    private volatile CqlSession session;

    protected abstract String contactHost();
    protected abstract int    contactPort();
    protected abstract String localDatacenter();

    protected CqlSession session() {
        if (session == null) {
            synchronized (this) {
                if (session == null) {
                    session = CqlSession.builder()
                        .addContactPoint(new InetSocketAddress(contactHost(), contactPort()))
                        .withLocalDatacenter(localDatacenter())
                        .build();
                }
            }
        }
        return session;
    }

    // ── CPU monitor factory ───────────────────────────────────────────────────

    private CpuMonitor newCpuMonitor() {
        return new CpuMonitor(getEngine().containerName);
    }

    // ── Baseline RTT measurement ──────────────────────────────────────────────

    /**
     * Measures the average round-trip overhead per CQL operation
     * (network + driver serialization) using 50 lightweight pings.
     *
     * Subtracted from each per-operation timer so reported latency reflects
     * DB-side index processing time only.
     */
    protected long measureBaselineNs() {
        PreparedStatement ps = session().prepare("SELECT now() FROM system.local");
        long total = 0;
        for (int i = 0; i < 50; i++) {
            long t = System.nanoTime();
            session().execute(ps.bind());
            total += System.nanoTime() - t;
        }
        return total / 50;
    }

    // ── DbBenchmarkRunner ─────────────────────────────────────────────────────

    @Override
    public boolean isAvailable() {
        try (CqlSession probe = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(contactHost(), contactPort()))
                .withLocalDatacenter(localDatacenter())
                .withConfigLoader(
                    com.datastax.oss.driver.api.core.config.DriverConfigLoader.programmaticBuilder()
                        .withDuration(
                            com.datastax.oss.driver.api.core.config.DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT,
                            java.time.Duration.ofSeconds(2))
                        .withDuration(
                            com.datastax.oss.driver.api.core.config.DefaultDriverOption.CONTROL_CONNECTION_TIMEOUT,
                            java.time.Duration.ofSeconds(2))
                        .build())
                .build()) {
            probe.execute("SELECT release_version FROM system.local");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void setupSchema() {
        CqlSession s = session();
        s.execute("CREATE KEYSPACE IF NOT EXISTS " + KEYSPACE +
            " WITH replication = {'class':'SimpleStrategy','replication_factor':1}");
        s.execute("DROP TABLE IF EXISTS " + KEYSPACE + "." + TABLE);
        s.execute("""
            CREATE TABLE %s.%s (
                id         bigint  PRIMARY KEY,
                name       text,
                price      decimal,
                category   text,
                created_at timestamp
            )
            """.formatted(KEYSPACE, TABLE));
        s.execute("CREATE INDEX IF NOT EXISTS ON " + KEYSPACE + "." + TABLE + "(category)");
    }

    @Override
    public void teardown() {
        try { session().execute("DROP TABLE IF EXISTS " + KEYSPACE + "." + TABLE); }
        catch (Exception ignored) {}
    }

    // ── INSERT ONLY ───────────────────────────────────────────────────────────

    @Override
    public BenchmarkResult runInsertOnly(int rowCount) {
        PreparedStatement ps = session().prepare(
            "INSERT INTO " + KEYSPACE + "." + TABLE +
            " (id, name, price, category, created_at) VALUES (?,?,?,?,toTimestamp(now()))");

        LatencyTracker tracker = new LatencyTracker();
        long baseline          = measureBaselineNs();

        double cpu = newCpuMonitor().measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < rowCount; i++) {
                long t = System.nanoTime();
                session().execute(ps.bind(
                    (long) i + 1,
                    "Product-" + (i + 1),
                    randPrice(rng),
                    CATEGORIES[rng.nextInt(CATEGORIES.length)]));
                tracker.record(Math.max(0L, System.nanoTime() - t - baseline));
            }
        });
        return build(WorkloadType.INSERT_ONLY, rowCount, tracker, cpu);
    }

    // ── UPDATE HEAVY ─────────────────────────────────────────────────────────

    @Override
    public BenchmarkResult runUpdateHeavy(int rowCount) {
        PreparedStatement ps = session().prepare(
            "UPDATE " + KEYSPACE + "." + TABLE +
            " SET price = ?, category = ? WHERE id = ?");

        LatencyTracker tracker = new LatencyTracker();
        long baseline          = measureBaselineNs();

        double cpu = newCpuMonitor().measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < rowCount; i++) {
                long id = (long) rng.nextInt(rowCount) + 1;
                long t  = System.nanoTime();
                session().execute(ps.bind(randPrice(rng),
                    CATEGORIES[rng.nextInt(CATEGORIES.length)], id));
                tracker.record(Math.max(0L, System.nanoTime() - t - baseline));
            }
        });
        return build(WorkloadType.UPDATE_HEAVY, rowCount, tracker, cpu);
    }

    // ── POINT READ ────────────────────────────────────────────────────────────

    @Override
    public BenchmarkResult runPointRead(int iterations) {
        PreparedStatement ps = session().prepare(
            "SELECT * FROM " + KEYSPACE + "." + TABLE + " WHERE id = ?");

        LatencyTracker tracker = new LatencyTracker();
        long baseline          = measureBaselineNs();

        double cpu = newCpuMonitor().measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < iterations; i++) {
                long id = (long) rng.nextInt(iterations) + 1;
                long t  = System.nanoTime();
                session().execute(ps.bind(id));
                tracker.record(Math.max(0L, System.nanoTime() - t - baseline));
            }
        });
        return build(WorkloadType.POINT_READ, iterations, tracker, cpu);
    }

    // ── RANGE READ ────────────────────────────────────────────────────────────

    @Override
    public BenchmarkResult runRangeRead(int iterations) {
        PreparedStatement ps = session().prepare(
            "SELECT * FROM " + KEYSPACE + "." + TABLE +
            " WHERE price >= ? AND price <= ? LIMIT 200 ALLOW FILTERING");

        LatencyTracker tracker = new LatencyTracker();
        long baseline          = measureBaselineNs();

        double cpu = newCpuMonitor().measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < iterations; i++) {
                double base = rng.nextDouble() * 900;
                BigDecimal lo = BigDecimal.valueOf(base).setScale(2, RoundingMode.HALF_UP);
                BigDecimal hi = lo.add(BigDecimal.valueOf(100));
                long t = System.nanoTime();
                session().execute(ps.bind(lo, hi));
                tracker.record(Math.max(0L, System.nanoTime() - t - baseline));
            }
        });
        return build(WorkloadType.RANGE_READ, iterations, tracker, cpu);
    }

    // ── DELETE HEAVY ──────────────────────────────────────────────────────────

    @Override
    public BenchmarkResult runDeleteHeavy(int rowCount) {
        PreparedStatement ps = session().prepare(
            "DELETE FROM " + KEYSPACE + "." + TABLE + " WHERE id = ?");

        LatencyTracker tracker = new LatencyTracker();
        long baseline          = measureBaselineNs();

        double cpu = newCpuMonitor().measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < rowCount; i++) {
                long id = (long) rng.nextInt(rowCount * 2) + 1;
                long t  = System.nanoTime();
                session().execute(ps.bind(id));
                tracker.record(Math.max(0L, System.nanoTime() - t - baseline));
            }
        });
        return build(WorkloadType.DELETE_HEAVY, rowCount, tracker, cpu);
    }

    // ── MIXED (70% read / 30% write) ─────────────────────────────────────────

    @Override
    public BenchmarkResult runMixed(int totalOps) {
        PreparedStatement readPs = session().prepare(
            "SELECT * FROM " + KEYSPACE + "." + TABLE + " WHERE id = ?");
        PreparedStatement writePs = session().prepare(
            "UPDATE " + KEYSPACE + "." + TABLE + " SET price = ? WHERE id = ?");

        LatencyTracker tracker = new LatencyTracker();
        long baseline          = measureBaselineNs();

        double cpu = newCpuMonitor().measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < totalOps; i++) {
                long id = (long) rng.nextInt(totalOps) + 1;
                long t  = System.nanoTime();
                if (rng.nextInt(10) < 7) {
                    session().execute(readPs.bind(id));
                } else {
                    session().execute(writePs.bind(randPrice(rng), id));
                }
                tracker.record(Math.max(0L, System.nanoTime() - t - baseline));
            }
        });
        return build(WorkloadType.MIXED, totalOps, tracker, cpu);
    }

    // ── STORAGE SIZE ──────────────────────────────────────────────────────────

    @Override
    public BenchmarkResult getStorageSize() {
        try {
            var rs = session().execute(
                "SELECT mean_partition_size, partitions_count " +
                "FROM system.size_estimates " +
                "WHERE keyspace_name='" + KEYSPACE + "' AND table_name='" + TABLE + "'");
            List<Row> rows = rs.all();
            long total = rows.stream()
                .mapToLong(r -> r.getLong("mean_partition_size") * r.getLong("partitions_count"))
                .sum();
            return new BenchmarkResult(getEngine(), WorkloadType.STORAGE_SIZE,
                0, 0, 0, 0, 0, 0, -1, total, "success", "");
        } catch (Exception e) {
            return BenchmarkResult.error(getEngine(), WorkloadType.STORAGE_SIZE, e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    protected BigDecimal randPrice(Random rng) {
        return BigDecimal.valueOf(rng.nextDouble() * 999 + 1).setScale(2, RoundingMode.HALF_UP);
    }

    protected BenchmarkResult build(WorkloadType wl, int opCount, LatencyTracker t, double cpu) {
        return new BenchmarkResult(
            getEngine(), wl, opCount,
            round2(t.throughputOpsPerSec()),
            round2(t.avgMs()), round2(t.p95Ms()), round2(t.p99Ms()),
            round2(cpu), -1, -1, "success", ""
        );
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
