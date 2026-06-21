package com.example.benchmark.controller;

import com.example.benchmark.model.BenchmarkProperties;
import com.example.benchmark.model.BenchmarkResult;
import com.example.benchmark.model.DbEngine;
import com.example.benchmark.service.BenchmarkOrchestrator;
import com.example.benchmark.service.OrchestratorService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/benchmark")
@CrossOrigin
public class BenchmarkController {

    private final BenchmarkOrchestrator orchestrator;
    private final Optional<OrchestratorService> orchestratorService;
    private final BenchmarkProperties props;

    public BenchmarkController(BenchmarkOrchestrator orchestrator,
                               Optional<OrchestratorService> orchestratorService,
                               BenchmarkProperties props) {
        this.orchestrator        = orchestrator;
        this.orchestratorService = orchestratorService;
        this.props               = props;
    }

    private boolean isOrchestratorMode() {
        return "orchestrator".equals(props.getMode());
    }

    private static final List<DbEngine> ORCHESTRATOR_ENGINES = List.of(
        DbEngine.POSTGRESQL, DbEngine.MYSQL_INNODB, DbEngine.MYSQL_MYROCKS,
        DbEngine.COCKROACHDB, DbEngine.CASSANDRA, DbEngine.SCYLLADB
    );

    @GetMapping("/engines")
    public List<Map<String, Object>> engines() {
        List<DbEngine> engines = isOrchestratorMode()
            ? ORCHESTRATOR_ENGINES
            : orchestrator.availableEngines();
        return engines.stream()
            .map(e -> Map.<String, Object>of(
                "id",          e.name(),
                "displayName", e.displayName,
                "indexType",   e.indexType,
                "color",       e.color,
                "available",   true))
            .toList();
    }

    /** Starts benchmark asynchronously and returns 202 immediately. */
    @PostMapping("/run")
    public ResponseEntity<Void> run(
            @RequestParam(defaultValue = "5000") int rowCount,
            @RequestParam(defaultValue = "1000") int readIter,
            @RequestParam(required = false)      Set<String> engines) {

        if (isOrchestratorMode()) {
            OrchestratorService svc = orchestratorService.orElseThrow();
            if (svc.isRunning()) return ResponseEntity.status(409).build();
            svc.startOrchestration(rowCount, readIter);
            return ResponseEntity.accepted().build();
        }

        if (orchestrator.isRunning()) return ResponseEntity.status(409).build();
        CompletableFuture.runAsync(() -> orchestrator.runAll(engines, rowCount, readIter));
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        if (isOrchestratorMode()) {
            OrchestratorService svc = orchestratorService.orElseThrow();
            return Map.of("running", svc.isRunning(), "phase", svc.getPhase());
        }
        return Map.of("running", orchestrator.isRunning());
    }

    @GetMapping("/results")
    public List<BenchmarkResult> lastResults() {
        if (isOrchestratorMode()) {
            return orchestratorService.orElseThrow().getLastResults();
        }
        return orchestrator.getLastResults();
    }

    @GetMapping("/results/csv")
    public ResponseEntity<String> downloadCsv() throws Exception {
        List<BenchmarkResult> results = lastResults();
        if (results.isEmpty()) return ResponseEntity.noContent().build();

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
                    r.dbEngine().displayName, r.dbEngine().indexType,
                    r.workloadType().displayName,
                    r.operationCount(),
                    fmt(r.throughputOpsPerSec()), fmt(r.avgLatencyMs()),
                    fmt(r.p95LatencyMs()), fmt(r.p99LatencyMs()),
                    fmt(r.cpuUsagePercent()),
                    r.writeAmplificationFactor() < 0 ? "N/A" : fmt(r.writeAmplificationFactor()),
                    r.storageSizeBytes() < 0 ? "N/A" : r.storageSizeBytes(),
                    r.status(), r.notes()
                );
            }
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"benchmark-results.csv\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(sw.toString());
    }

    private String fmt(double v) { return String.format("%.2f", v); }
}