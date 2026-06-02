package com.example.benchmark.model;

public enum DbEngine {
    POSTGRESQL("PostgreSQL",    "B-tree",   "#3b82f6"),
    MYSQL_INNODB("MySQL InnoDB","B-tree",   "#f59e0b"),
    MYSQL_MYROCKS("MyRocks",   "LSM-tree",  "#10b981"),
    CASSANDRA("Cassandra",     "LSM-tree",  "#8b5cf6"),
    SCYLLADB("ScyllaDB",       "LSM-tree",  "#ec4899"),
    ROCKSDB("RocksDB",         "LSM-tree",  "#06b6d4"),
    COCKROACHDB("CockroachDB", "B-tree",    "#f97316");

    public final String displayName;
    public final String indexType;
    public final String color;

    DbEngine(String displayName, String indexType, String color) {
        this.displayName = displayName;
        this.indexType   = indexType;
        this.color       = color;
    }
}
