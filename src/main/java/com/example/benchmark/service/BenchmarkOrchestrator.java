package com.example.benchmark.service;

import com.example.benchmark.model.BenchmarkProperties;
import com.example.benchmark.model.BenchmarkResult;
import com.example.benchmark.model.DbEngine;
import com.example.benchmark.model.WorkloadType;
import com.example.benchmark.service.runner.DbBenchmarkRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Service
public class BenchmarkOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkOrchestrator.class);

    private final List<DbBenchmarkRunner> runners;

    // Latest full-run results, keyed by DbEngine
    private final Map<DbEngine, List<BenchmarkResult>> lastResults = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public BenchmarkOrchestrator(List<DbBenchmarkRunner> runners, BenchmarkProperties props) {
        List<String> enabled = props.getEnabledEngines();
        if (enabled.isEmpty()) {
            this.runners = runners;
        } else {
            this.runners = runners.stream()
                .filter(r -> enabled.contains(r.getEngine().name()))
                .collect(Collectors.toList());
            log.info("Enabled engines: {}", enabled);
        }
    }

    public boolean isRunning() { return running; }

    public List<DbEngine> availableEngines() {
        return runners.stream()
            .filter(r -> {
                try { return r.isAvailable(); }
                catch (Exception e) { return false; }
            })
            .map(DbBenchmarkRunner::getEngine)
            .collect(Collectors.toList());
    }

    /**
     * Run all workloads for the given engines.
     * @param engineNames  null/empty = all available
     * @param rowCount     rows for insert/update/delete workloads
     * @param readIter     iterations for point/range read and mixed
     */
    public List<BenchmarkResult> runAll(Set<String> engineNames, int rowCount, int readIter) {
        running = true;
        lastResults.clear();
        List<BenchmarkResult> all = new CopyOnWriteArrayList<>();

        try {
            List<DbBenchmarkRunner> targets = runners.stream()
                .filter(r -> engineNames == null || engineNames.isEmpty()
                    || engineNames.contains(r.getEngine().name()))
                .collect(Collectors.toList());

            for (DbBenchmarkRunner runner : targets) {
                DbEngine engine = runner.getEngine();
                log.info("Starting benchmark for {}", engine.displayName);

                List<BenchmarkResult> engineResults = new ArrayList<>();
                try {
                    if (!runner.isAvailable()) {
                        log.warn("{} unavailable, skipping", engine.displayName);
                        for (WorkloadType wl : WorkloadType.values()) {
                            engineResults.add(BenchmarkResult.unavailable(engine, wl));
                        }
                        all.addAll(engineResults);
                        lastResults.put(engine, engineResults);
                        continue;
                    }

                    log.info("  [{}] setupSchema", engine.displayName);
                    runner.setupSchema();

                    log.info("  [{}] INSERT_ONLY ({})", engine.displayName, rowCount);
                    engineResults.add(safe(runner, WorkloadType.INSERT_ONLY, () -> runner.runInsertOnly(rowCount)));

                    engineResults.add(safe(runner, WorkloadType.STORAGE_SIZE, runner::getStorageSize));

                    log.info("  [{}] UPDATE_HEAVY ({})", engine.displayName, rowCount / 5);
                    engineResults.add(safe(runner, WorkloadType.UPDATE_HEAVY, () -> runner.runUpdateHeavy(rowCount / 5)));

                    log.info("  [{}] POINT_READ ({})", engine.displayName, readIter);
                    engineResults.add(safe(runner, WorkloadType.POINT_READ, () -> runner.runPointRead(readIter)));

                    log.info("  [{}] RANGE_READ ({})", engine.displayName, readIter / 5);
                    engineResults.add(safe(runner, WorkloadType.RANGE_READ, () -> runner.runRangeRead(readIter / 5)));

                    log.info("  [{}] DELETE_HEAVY ({})", engine.displayName, rowCount / 10);
                    engineResults.add(safe(runner, WorkloadType.DELETE_HEAVY, () -> runner.runDeleteHeavy(rowCount / 10)));

                    log.info("  [{}] MIXED ({})", engine.displayName, readIter);
                    engineResults.add(safe(runner, WorkloadType.MIXED, () -> runner.runMixed(readIter)));

                    engineResults.add(safe(runner, WorkloadType.STORAGE_SIZE, runner::getStorageSize));

                    log.info("  [{}] teardown", engine.displayName);
                    runner.teardown();

                } catch (Exception e) {
                    log.error("Fatal error benchmarking {}: {}", engine.displayName, e.getMessage(), e);
                    for (WorkloadType wl : WorkloadType.values()) {
                        if (engineResults.stream().noneMatch(r -> r.workloadType() == wl)) {
                            engineResults.add(BenchmarkResult.error(engine, wl, e.getMessage()));
                        }
                    }
                }

                all.addAll(engineResults);
                lastResults.put(engine, engineResults);
            }
        } finally {
            running = false;
        }

        return all;
    }

    public List<BenchmarkResult> getLastResults() {
        return lastResults.values().stream()
            .flatMap(Collection::stream)
            .collect(Collectors.toList());
    }

    public List<BenchmarkResult> getLastResultsForEngine(DbEngine engine) {
        return lastResults.getOrDefault(engine, List.of());
    }

    private BenchmarkResult safe(DbBenchmarkRunner runner, WorkloadType wl,
                                  java.util.concurrent.Callable<BenchmarkResult> fn) {
        try {
            return fn.call();
        } catch (Exception e) {
            log.error("Workload error on {} [{}]: {}", runner.getEngine().displayName, wl, e.getMessage());
            return BenchmarkResult.error(runner.getEngine(), wl, e.getMessage());
        }
    }
}
