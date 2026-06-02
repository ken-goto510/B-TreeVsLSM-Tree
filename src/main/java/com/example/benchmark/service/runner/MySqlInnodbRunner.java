package com.example.benchmark.service.runner;

import com.example.benchmark.model.BenchmarkProperties;
import com.example.benchmark.model.DbEngine;
import org.springframework.stereotype.Component;

@Component
public class MySqlInnodbRunner extends AbstractJdbcRunner {

    private final BenchmarkProperties props;

    public MySqlInnodbRunner(BenchmarkProperties props) { this.props = props; }

    @Override public DbEngine getEngine() { return DbEngine.MYSQL_INNODB; }

    @Override protected String jdbcUrl()      { return props.getMysql().getUrl(); }
    @Override protected String jdbcUsername() { return props.getMysql().getUsername(); }
    @Override protected String jdbcPassword() { return props.getMysql().getPassword(); }
    @Override protected String driverClass()  { return "com.mysql.cj.jdbc.Driver"; }
    @Override protected String tableName()    { return "products_innodb"; }

    @Override
    protected String createTableDdl() {
        return """
            CREATE TABLE IF NOT EXISTS products_innodb (
                id         BIGINT        NOT NULL,
                name       VARCHAR(255)  NOT NULL,
                price      DECIMAL(10,2) NOT NULL,
                category   VARCHAR(100)  NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                INDEX idx_innodb_price    (price),
                INDEX idx_innodb_category (category)
            ) ENGINE=InnoDB CHARACTER SET utf8mb4
            """;
    }

    @Override
    protected String dropTableDdl() {
        return "DROP TABLE IF EXISTS products_innodb";
    }

    @Override
    protected long queryStorageSizeBytes() {
        Long bytes = jdbc().queryForObject(
            "SELECT data_length + index_length FROM information_schema.tables " +
            "WHERE table_schema = DATABASE() AND table_name = 'products_innodb'",
            Long.class);
        return bytes == null ? -1 : bytes;
    }
}
