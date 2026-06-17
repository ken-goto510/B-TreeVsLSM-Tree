package com.example.benchmark.model;

public enum DbEngine {
    POSTGRESQL("PostgreSQL",    "B-tree",   "#3b82f6", "postgres-benchmark"),
    MYSQL_INNODB("MySQL InnoDB","B-tree",   "#f59e0b", "percona-benchmark"),
    MYSQL_MYROCKS("MyRocks",   "LSM-tree",  "#10b981", "percona-benchmark"),
    CASSANDRA("Cassandra",     "LSM-tree",  "#8b5cf6", "cassandra-benchmark"),
    SCYLLADB("ScyllaDB",       "LSM-tree",  "#ec4899", "scylladb-benchmark"),
    ROCKSDB("RocksDB",         "LSM-tree",  "#06b6d4", null),
    COCKROACHDB("CockroachDB", "B-tree",    "#f97316", "cockroachdb-benchmark");

    public final String displayName;
    public final String indexType;
    public final String color;
    /** Docker container name to sample CPU from; null for embedded engines (RocksDB). */
    public final String containerName;

    DbEngine(String displayName, String indexType, String color, String containerName) {
        this.displayName   = displayName;
        this.indexType     = indexType;
        this.color         = color;
        this.containerName = containerName;
    }
}
