package com.example.benchmark.service.runner;

import com.example.benchmark.model.BenchmarkResult;
import com.example.benchmark.model.DbEngine;

public interface DbBenchmarkRunner {
    DbEngine getEngine();
    boolean  isAvailable();
    void     setupSchema();
    void     teardown();
    BenchmarkResult runInsertOnly(int rowCount);
    BenchmarkResult runUpdateHeavy(int rowCount);
    BenchmarkResult runPointRead(int iterations);
    BenchmarkResult runRangeRead(int iterations);
    BenchmarkResult runDeleteHeavy(int rowCount);
    BenchmarkResult runMixed(int totalOps);
    BenchmarkResult getStorageSize();
}
