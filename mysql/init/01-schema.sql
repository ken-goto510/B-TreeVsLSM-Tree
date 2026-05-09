-- InnoDB table: B-tree index (default MySQL engine)
CREATE TABLE IF NOT EXISTS products_innodb (
    id         BIGINT        NOT NULL,
    name       VARCHAR(255)  NOT NULL,
    price      DECIMAL(10,2) NOT NULL,
    category   VARCHAR(100)  NOT NULL,
    created_at TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_innodb_category (category),
    INDEX idx_innodb_price    (price)
) ENGINE=InnoDB CHARACTER SET utf8mb4;

-- MyRocks table: SSTable / LSM-tree index (RocksDB engine)
CREATE TABLE IF NOT EXISTS products_myrocks (
    id         BIGINT        NOT NULL,
    name       VARCHAR(255)  NOT NULL,
    price      DECIMAL(10,2) NOT NULL,
    category   VARCHAR(100)  NOT NULL,
    created_at TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_myrocks_category (category),
    INDEX idx_myrocks_price    (price)
) ENGINE=ROCKSDB CHARACTER SET utf8mb4;