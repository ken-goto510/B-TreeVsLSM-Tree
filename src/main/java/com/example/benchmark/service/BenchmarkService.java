package com.example.benchmark.service;

import com.example.benchmark.model.BenchmarkResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class BenchmarkService {

    private static final String[] CATEGORIES = {
            "Electronics", "Books", "Clothing", "Food", "Sports",
            "Toys", "Home", "Garden", "Beauty", "Automotive"
    };

    private final JdbcTemplate jdbc;

    public BenchmarkService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- Setup / Reset ----

    public void reset() {
        jdbc.execute("TRUNCATE TABLE products_innodb");
        jdbc.execute("TRUNCATE TABLE products_myrocks");
    }

    public int currentRowCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM products_innodb", Integer.class);
        return count == null ? 0 : count;
    }

    // ---- Benchmarks ----

    /**
     * Bulk INSERT: batch insert N rows into both engines, compare write throughput.
     * MyRocks (LSM-tree) is typically faster for sequential/bulk writes because
     * writes go to a memtable first, then are flushed as immutable SSTables.
     * InnoDB (B-tree) must maintain page structure on every write.
     */
    @Transactional
    public BenchmarkResult benchmarkBulkInsert(int count) {
        List<Object[]> rows = generateRows(count);

        long innodbMs = timeInsert("products_innodb", rows);
        long myrocksMs = timeInsert("products_myrocks", rows);

        return BenchmarkResult.of(
                "BULK_INSERT",
                count + "件の一括INSERT。MyRocksはLSM-treeでmemtableに書き込み後SSTableにflushするため書込みが速い。",
                innodbMs, myrocksMs, count
        );
    }

    /**
     * Point Lookup by PK: random PK lookups.
     * InnoDB B-tree delivers O(log N) PK lookups in a well-cached clustered index.
     * MyRocks must check memtable + multiple SSTable levels (read amplification).
     */
    public BenchmarkResult benchmarkPointLookup(int iterations) {
        int total = currentRowCount();
        if (total == 0) return noDataResult("POINT_LOOKUP");

        Random rng = ThreadLocalRandom.current();
        long innodbMs = timePointLookup("products_innodb", total, iterations, rng);
        long myrocksMs = timePointLookup("products_myrocks", total, iterations, rng);

        return BenchmarkResult.of(
                "POINT_LOOKUP",
                "PK による点検索 " + iterations + "回。InnoDB B-treeはクラスタ化インデックスでキャッシュ効率が高い。",
                innodbMs, myrocksMs, iterations
        );
    }

    /**
     * Range Scan by price: BETWEEN query using secondary index.
     * InnoDB B-tree supports efficient range scans (leaf-level linked list).
     * MyRocks also supports range scans but may involve reading multiple SSTable files.
     */
    public BenchmarkResult benchmarkRangeScan(int iterations) {
        if (currentRowCount() == 0) return noDataResult("RANGE_SCAN");

        long innodbMs = timeRangeScan("products_innodb", "idx_innodb_price", iterations);
        long myrocksMs = timeRangeScan("products_myrocks", "idx_myrocks_price", iterations);

        return BenchmarkResult.of(
                "RANGE_SCAN",
                "price の BETWEEN 範囲検索 " + iterations + "回。B-treeはリーフノードの連結リストで範囲スキャンが得意。",
                innodbMs, myrocksMs, iterations
        );
    }

    /**
     * Secondary Index Scan by category: equality lookup on non-unique index.
     */
    public BenchmarkResult benchmarkSecondaryIndex(int iterations) {
        if (currentRowCount() == 0) return noDataResult("SECONDARY_INDEX");

        Random rng = ThreadLocalRandom.current();
        long innodbMs = timeSecondaryIndex("products_innodb", iterations, rng);
        long myrocksMs = timeSecondaryIndex("products_myrocks", iterations, rng);

        return BenchmarkResult.of(
                "SECONDARY_INDEX",
                "category セカンダリインデックス検索 " + iterations + "回。",
                innodbMs, myrocksMs, iterations
        );
    }

    /**
     * DELETE all rows.
     * MyRocks marks records as deleted (tombstone) in the memtable — very fast.
     * InnoDB must update B-tree pages immediately.
     */
    public BenchmarkResult benchmarkDelete() {
        int count = currentRowCount();
        if (count == 0) return noDataResult("DELETE");

        long innodbMs = timeDelete("products_innodb");
        long myrocksMs = timeDelete("products_myrocks");

        return BenchmarkResult.of(
                "DELETE",
                "全行 DELETE " + count + "件。MyRocksはtombstoneを書くだけなので高速。",
                innodbMs, myrocksMs, count
        );
    }

    public List<BenchmarkResult> runAll(int insertCount) {
        reset();
        List<BenchmarkResult> results = new ArrayList<>();
        results.add(benchmarkBulkInsert(insertCount));
        results.add(benchmarkPointLookup(1000));
        results.add(benchmarkRangeScan(200));
        results.add(benchmarkSecondaryIndex(200));
        results.add(benchmarkDelete());
        return results;
    }

    // ---- Private helpers ----

    private long timeInsert(String table, List<Object[]> rows) {
        long start = System.currentTimeMillis();
        jdbc.batchUpdate(insertSql(table), rows);
        return System.currentTimeMillis() - start;
    }

    private String insertSql(String table) {
        return "INSERT INTO " + table + " (id, name, price, category) VALUES (?,?,?,?)";
    }

    private long timePointLookup(String table, int total, int iterations, Random rng) {
        long start = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            long id = (long) rng.nextInt(total) + 1;
            jdbc.queryForList("SELECT * FROM " + table + " WHERE id = ?", id);
        }
        return System.currentTimeMillis() - start;
    }

    private long timeRangeScan(String table, String indexHint, int iterations) {
        long start = System.currentTimeMillis();
        Random rng = ThreadLocalRandom.current();
        for (int i = 0; i < iterations; i++) {
            double base = rng.nextDouble() * 900;
            BigDecimal lo = BigDecimal.valueOf(base).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal hi = lo.add(BigDecimal.valueOf(100));
            jdbc.queryForList(
                    "SELECT * FROM " + table + " USE INDEX (" + indexHint + ") WHERE price BETWEEN ? AND ?",
                    lo, hi
            );
        }
        return System.currentTimeMillis() - start;
    }

    private long timeSecondaryIndex(String table, int iterations, Random rng) {
        long start = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            String cat = CATEGORIES[rng.nextInt(CATEGORIES.length)];
            jdbc.queryForList("SELECT * FROM " + table + " WHERE category = ? LIMIT 100", cat);
        }
        return System.currentTimeMillis() - start;
    }

    private long timeDelete(String table) {
        long start = System.currentTimeMillis();
        jdbc.execute("DELETE FROM " + table);
        return System.currentTimeMillis() - start;
    }

    private List<Object[]> generateRows(int count) {
        List<Object[]> rows = new ArrayList<>(count);
        Random rng = ThreadLocalRandom.current();
        for (int i = 1; i <= count; i++) {
            rows.add(new Object[]{
                    (long) i,
                    "Product-" + i,
                    BigDecimal.valueOf(rng.nextDouble() * 999 + 1).setScale(2, java.math.RoundingMode.HALF_UP),
                    CATEGORIES[rng.nextInt(CATEGORIES.length)]
            });
        }
        return rows;
    }

    private BenchmarkResult noDataResult(String op) {
        return BenchmarkResult.of(op, "データがありません。先にINSERTを実行してください。", 0, 0, 0);
    }
}