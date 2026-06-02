-- Tables are created/dropped at runtime by the benchmark runners.
-- This file just ensures the plugin is loaded and the DB exists.

-- InnoDB products table (created by MySqlInnodbRunner at runtime)
-- MyRocks products table (created by MyRocksRunner at runtime)

-- Verify RocksDB plugin is available
-- SHOW ENGINES;  -- should include ROCKSDB
