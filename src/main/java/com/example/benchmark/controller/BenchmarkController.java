package com.example.benchmark.controller;

import com.example.benchmark.model.BenchmarkResult;
import com.example.benchmark.model.DbEngine;
import com.example.benchmark.service.BenchmarkOrchestrator;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/benchmark")
@CrossOrigin
public class BenchmarkController {

    private final BenchmarkOrchestrator orchestrator;

    public BenchmarkController(BenchmarkOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /** List all engines and their availability */
    @GetMapping("/engines")
    public List<Map<String, Object>> engines() {
        return orchestrator.availableEngines().stream()
            .map(e -> Map.<String, Object>of(
                "id",          e.name(),
                "displayName", e.displayName,
                "indexType",   e.indexType,
                "color",       e.color,
                "available",   true))
            .toList();
    }

    /** Run full benchmark suite */
    @PostMapping("/run")
    public ResponseEntity<List<BenchmarkResult>> run(
            @RequestParam(defaultValue = "5000")  int rowCount,
            @RequestParam(defaultValue = "1000")  int readIter,
            @RequestParam(required = false)       Set<String> engines) {

        if (orchestrator.isRunning()) {
            return ResponseEntity.status(409).body(List.of());
        }
        List<BenchmarkResult> results = orchestrator.runAll(engines, rowCount, readIter);
        return ResponseEntity.ok(results);
    }

    /** Return last results without re-running */
    @GetMapping("/results")
    public List<BenchmarkResult> lastResults() {
        return orchestrator.getLastResults();
    }

    /** Download last results as CSV */
    @GetMapping("/results/csv")
    public ResponseEntity<String> downloadCsv() throws Exception {
        List<BenchmarkResult> results = orchestrator.getLastResults();
        if (results.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        StringWriter sw = new StringWriter();
        CSVFormat fmt = CSVFormat.DEFAULT.builder()
            .setHeader("DB Engine", "Index Type", "Workload",
                       "Operations", "Throughput(ops/s)", "Avg Latency(ms)",
                       "P95 Latency(ms)", "P99 Latency(ms)", "CPU(%)",
                       "WAF", "Storage(bytes)", "Status", "Notes")
            .build();

        try (CSVPrinter csv = new CSVPrinter(sw, fmt)) {
            for (BenchmarkResult r : results) {
                csv.printRecord(
                    r.dbEngine().displayName,
                    r.dbEngine().indexType,
                    r.workloadType().displayName,
                    r.operationCount(),
                    fmt(r.throughputOpsPerSec()),
                    fmt(r.avgLatencyMs()),
                    fmt(r.p95LatencyMs()),
                    fmt(r.p99LatencyMs()),
                    fmt(r.cpuUsagePercent()),
                    r.writeAmplificationFactor() < 0 ? "N/A" : fmt(r.writeAmplificationFactor()),
                    r.storageSizeBytes() < 0 ? "N/A" : r.storageSizeBytes(),
                    r.status(),
                    r.notes()
                );
            }
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"benchmark-results.csv\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(sw.toString());
    }

    /** Check if a benchmark is currently running */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
            "running",          orchestrator.isRunning(),
            "availableEngines", orchestrator.availableEngines().stream()
                                            .map(DbEngine::name).toList()
        );
    }

    private String fmt(double v) {
        return String.format("%.2f", v);
    }
}
