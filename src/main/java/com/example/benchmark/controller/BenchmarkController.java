package com.example.benchmark.controller;

import com.example.benchmark.model.BenchmarkResult;
import com.example.benchmark.service.BenchmarkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/benchmark")
@CrossOrigin
public class BenchmarkController {

    private final BenchmarkService service;

    public BenchmarkController(BenchmarkService service) {
        this.service = service;
    }

    /** 全テーブルをリセット */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> reset() {
        service.reset();
        return ResponseEntity.ok(Map.of("status", "ok", "message", "両テーブルをリセットしました"));
    }

    /** 現在の行数確認 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("rowCount", service.currentRowCount());
    }

    /** Bulk INSERT ベンチマーク */
    @PostMapping("/insert")
    public BenchmarkResult insert(@RequestParam(defaultValue = "10000") int count) {
        return service.benchmarkBulkInsert(count);
    }

    /** Point Lookup (PK) ベンチマーク */
    @GetMapping("/point-lookup")
    public BenchmarkResult pointLookup(@RequestParam(defaultValue = "1000") int iterations) {
        return service.benchmarkPointLookup(iterations);
    }

    /** Range Scan (price BETWEEN) ベンチマーク */
    @GetMapping("/range-scan")
    public BenchmarkResult rangeScan(@RequestParam(defaultValue = "200") int iterations) {
        return service.benchmarkRangeScan(iterations);
    }

    /** Secondary Index (category) ベンチマーク */
    @GetMapping("/secondary-index")
    public BenchmarkResult secondaryIndex(@RequestParam(defaultValue = "200") int iterations) {
        return service.benchmarkSecondaryIndex(iterations);
    }

    /** DELETE ベンチマーク */
    @DeleteMapping("/delete")
    public BenchmarkResult delete() {
        return service.benchmarkDelete();
    }

    /** 全ベンチマークを順番に実行 */
    @PostMapping("/run-all")
    public List<BenchmarkResult> runAll(@RequestParam(defaultValue = "10000") int insertCount) {
        return service.runAll(insertCount);
    }
}