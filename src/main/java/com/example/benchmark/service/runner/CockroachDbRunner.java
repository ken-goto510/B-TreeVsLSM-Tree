package com.example.benchmark.service.runner;

import com.example.benchmark.model.BenchmarkProperties;
import com.example.benchmark.model.DbEngine;
import org.springframework.stereotype.Component;

@Component
public class CockroachDbRunner extends AbstractJdbcRunner {

    private final BenchmarkProperties props;

    public CockroachDbRunner(BenchmarkProperties props) { this.props = props; }

    @Override public DbEngine getEngine() { return DbEngine.COCKROACHDB; }

    @Override protected String jdbcUrl()      { return props.getCockroachdb().getUrl(); }
    @Override protected String jdbcUsername() { return props.getCockroachdb().getUsername(); }
    @Override protected String jdbcPassword() { return props.getCockroachdb().getPassword(); }
    @Override protected String driverClass()  { return "org.postgresql.Driver"; }
    @Override protected String tableName()    { return "products"; }

    @Override
    public void setupSchema() {
        // CockroachDB needs the database/user to exist first
        try {
            jdbc().execute("CREATE DATABASE IF NOT EXISTS benchmark");
        } catch (Exception ignored) {}
        try {
            jdbc().execute("SET DATABASE = defaultdb");
        } catch (Exception ignored) {}
        jdbc().execute(dropTableDdl());
        jdbc().execute(createTableDdl());
    }

    @Override
    protected String createTableDdl() {
        return """
            CREATE TABLE IF NOT EXISTS products (
                id         BIGINT        NOT NULL,
                name       VARCHAR(255)  NOT NULL,
                price      DECIMAL(10,2) NOT NULL,
                category   VARCHAR(100)  NOT NULL,
                created_at TIMESTAMP DEFAULT now(),
                PRIMARY KEY (id)
            );
            CREATE INDEX IF NOT EXISTS idx_crdb_price    ON products(price);
            CREATE INDEX IF NOT EXISTS idx_crdb_category ON products(category);
            """;
    }

    @Override
    protected String dropTableDdl() {
        return "DROP TABLE IF EXISTS products";
    }

    @Override
    protected long queryStorageSizeBytes() {
        // CockroachDB doesn't expose table byte size easily; return estimate
        Long rows = jdbc().queryForObject("SELECT COUNT(*) FROM products", Long.class);
        return rows == null ? -1 : rows * 80L;
    }
}
