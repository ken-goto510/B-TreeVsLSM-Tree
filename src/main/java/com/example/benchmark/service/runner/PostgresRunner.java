package com.example.benchmark.service.runner;

import com.example.benchmark.model.BenchmarkProperties;
import com.example.benchmark.model.DbEngine;
import org.springframework.stereotype.Component;

@Component
public class PostgresRunner extends AbstractJdbcRunner {

    private final BenchmarkProperties props;

    public PostgresRunner(BenchmarkProperties props) { this.props = props; }

    @Override public DbEngine getEngine() { return DbEngine.POSTGRESQL; }

    @Override protected String jdbcUrl()      { return props.getPostgresql().getUrl(); }
    @Override protected String jdbcUsername() { return props.getPostgresql().getUsername(); }
    @Override protected String jdbcPassword() { return props.getPostgresql().getPassword(); }
    @Override protected String driverClass()  { return "org.postgresql.Driver"; }
    @Override protected String tableName()    { return "products"; }

    @Override
    protected String createTableDdl() {
        return """
            CREATE TABLE IF NOT EXISTS products (
                id         BIGINT        NOT NULL,
                name       VARCHAR(255)  NOT NULL,
                price      DECIMAL(10,2) NOT NULL,
                category   VARCHAR(100)  NOT NULL,
                created_at TIMESTAMP DEFAULT NOW(),
                PRIMARY KEY (id)
            );
            CREATE INDEX IF NOT EXISTS idx_pg_price    ON products(price);
            CREATE INDEX IF NOT EXISTS idx_pg_category ON products(category);
            """;
    }

    @Override
    protected String dropTableDdl() {
        return "DROP TABLE IF EXISTS products";
    }

    @Override
    protected long queryStorageSizeBytes() {
        Long bytes = jdbc().queryForObject(
            "SELECT pg_total_relation_size('products')", Long.class);
        return bytes == null ? -1 : bytes;
    }
}
