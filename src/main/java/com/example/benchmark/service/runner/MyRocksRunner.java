package com.example.benchmark.service.runner;

import com.example.benchmark.model.BenchmarkProperties;
import com.example.benchmark.model.DbEngine;
import org.springframework.stereotype.Component;

@Component
public class MyRocksRunner extends AbstractJdbcRunner {

    private final BenchmarkProperties props;

    public MyRocksRunner(BenchmarkProperties props) { this.props = props; }

    @Override public DbEngine getEngine() { return DbEngine.MYSQL_MYROCKS; }

    @Override protected String jdbcUrl()      { return props.getMysql().getUrl(); }
    @Override protected String jdbcUsername() { return props.getMysql().getUsername(); }
    @Override protected String jdbcPassword() { return props.getMysql().getPassword(); }
    @Override protected String driverClass()  { return "com.mysql.cj.jdbc.Driver"; }
    @Override protected String tableName()    { return "products_myrocks"; }

    @Override
    protected String createTableDdl() {
        return """
            CREATE TABLE IF NOT EXISTS products_myrocks (
                id         BIGINT        NOT NULL,
                name       VARCHAR(255)  NOT NULL,
                price      DECIMAL(10,2) NOT NULL,
                category   VARCHAR(100)  NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                INDEX idx_myrocks_price    (price),
                INDEX idx_myrocks_category (category)
            ) ENGINE=ROCKSDB CHARACTER SET utf8mb4
            """;
    }

    @Override
    protected String dropTableDdl() {
        return "DROP TABLE IF EXISTS products_myrocks";
    }

    @Override
    protected long queryStorageSizeBytes() {
        // RocksDB doesn't maintain accurate info_schema sizes; use row estimate
        Long rows = jdbc().queryForObject(
            "SELECT COUNT(*) FROM products_myrocks", Long.class);
        return rows == null ? -1 : rows * 80L;  // ~80 bytes/row estimate
    }

    @Override
    protected double computeWaf(long userBytesWritten) {
        // Approximate WAF from RocksDB compaction stats via MyRocks status variables
        try {
            var rows = jdbc().queryForList(
                "SELECT VARIABLE_NAME, VARIABLE_VALUE FROM information_schema.GLOBAL_STATUS " +
                "WHERE VARIABLE_NAME IN ('Rocksdb_bytes_written','Rocksdb_compact_write_bytes')");

            long writtenToRocksdb = 0;
            long compactWritten   = 0;
            for (var row : rows) {
                String name = (String) row.get("VARIABLE_NAME");
                long   val  = parseLong(row.get("VARIABLE_VALUE"));
                if ("Rocksdb_bytes_written".equalsIgnoreCase(name))       writtenToRocksdb = val;
                else if ("Rocksdb_compact_write_bytes".equalsIgnoreCase(name)) compactWritten = val;
            }
            if (userBytesWritten <= 0 || writtenToRocksdb <= 0) return -1;
            return (double)(writtenToRocksdb + compactWritten) / userBytesWritten;
        } catch (Exception e) {
            return -1;
        }
    }

    private long parseLong(Object v) {
        if (v == null) return 0;
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
