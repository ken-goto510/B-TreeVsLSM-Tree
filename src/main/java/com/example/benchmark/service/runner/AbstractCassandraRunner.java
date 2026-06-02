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

    protected final CpuMonitor cpuMonitor = new CpuMonitor();
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

    @Override
    public boolean isAvailable() {
        try {
            session().execute("SELECT release_version FROM system.local");
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

    @Override
    public BenchmarkResult runInsertOnly(int rowCount) {
        PreparedStatement ps = session().prepare(
            "INSERT INTO " + KEYSPACE + "." + TABLE +
            " (id, name, price, category, created_at) VALUES (?,?,?,?,toTimestamp(now()))");

        LatencyTracker tracker = new LatencyTracker();
        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < rowCount; i++) {
                long t = System.nanoTime();
                session().execute(ps.bind(
                    (long) i + 1,
                    "Product-" + (i + 1),
                    randPrice(rng),
                    CATEGORIES[rng.nextInt(CATEGORIES.length)]));
                tracker.record(System.nanoTime() - t);
            }
        });
        return build(WorkloadType.INSERT_ONLY, rowCount, tracker, cpu);
    }

    @Override
    public BenchmarkResult runUpdateHeavy(int rowCount) {
        PreparedStatement ps = session().prepare(
            "UPDATE " + KEYSPACE + "." + TABLE +
            " SET price = ?, category = ? WHERE id = ?");

        LatencyTracker tracker = new LatencyTracker();
        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < rowCount; i++) {
                long id = (long) rng.nextInt(rowCount) + 1;
                long t  = System.nanoTime();
                session().execute(ps.bind(randPrice(rng),
                    CATEGORIES[rng.nextInt(CATEGORIES.length)], id));
                tracker.record(System.nanoTime() - t);
            }
        });
        return build(WorkloadType.UPDATE_HEAVY, rowCount, tracker, cpu);
    }

    @Override
    public BenchmarkResult runPointRead(int iterations) {
        PreparedStatement ps = session().prepare(
            "SELECT * FROM " + KEYSPACE + "." + TABLE + " WHERE id = ?");

        LatencyTracker tracker = new LatencyTracker();
        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < iterations; i++) {
                long id = (long) rng.nextInt(iterations) + 1;
                long t  = System.nanoTime();
                session().execute(ps.bind(id));
                tracker.record(System.nanoTime() - t);
            }
        });
        return build(WorkloadType.POINT_READ, iterations, tracker, cpu);
    }

    @Override
    public BenchmarkResult runRangeRead(int iterations) {
        // Cassandra doesn't support range queries on non-partition keys without ALLOW FILTERING
        PreparedStatement ps = session().prepare(
            "SELECT * FROM " + KEYSPACE + "." + TABLE +
            " WHERE price >= ? AND price <= ? ALLOW FILTERING LIMIT 200");

        LatencyTracker tracker = new LatencyTracker();
        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < iterations; i++) {
                double base = rng.nextDouble() * 900;
                BigDecimal lo = BigDecimal.valueOf(base).setScale(2, RoundingMode.HALF_UP);
                BigDecimal hi = lo.add(BigDecimal.valueOf(100));
                long t = System.nanoTime();
                session().execute(ps.bind(lo, hi));
                tracker.record(System.nanoTime() - t);
            }
        });
        return build(WorkloadType.RANGE_READ, iterations, tracker, cpu);
    }

    @Override
    public BenchmarkResult runDeleteHeavy(int rowCount) {
        PreparedStatement ps = session().prepare(
            "DELETE FROM " + KEYSPACE + "." + TABLE + " WHERE id = ?");

        LatencyTracker tracker = new LatencyTracker();
        double cpu = cpuMonitor.measureDuring(() -> {
            Random rng = ThreadLocalRandom.current();
            tracker.start();
            for (int i = 0; i < rowCount; i++) {
                long id = (long) rng.nextInt(rowCount * 2) + 1;
                long t  = System.nanoTime();
                session().execute(ps.bind(id));
                tracker.record(System.nanoTime() - t);
            }
        });
        return build(WorkloadType.DELETE_HEAVY, rowCount, tracker, cpu);
    }

    @Override
    public BenchmarkResult runMixed(int totalOps) {
        PreparedStatement readPs = session().prepare(
            "SELECT * FROM " + KEYSPACE + "." + TABLE + " WHERE id = ?");
        PreparedStatement writePs = session().prepare(
            "UPDATE " + KEYSPACE + "." + TABLE + " SET price = ? WHERE id = ?");

        LatencyTracker tracker = new LatencyTracker();
        double cpu = cpuMonitor.measureDuring(() -> {
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
                tracker.record(System.nanoTime() - t);
            }
        });
        return build(WorkloadType.MIXED, totalOps, tracker, cpu);
    }

    @Override
    public BenchmarkResult getStorageSize() {
        // Cassandra size estimation via system tables
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
