package com.example.benchmark.service;

import com.example.benchmark.model.BenchmarkProperties;
import com.example.benchmark.model.BenchmarkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Activated when BENCHMARK_MODE=worker.
 * Runs the benchmark for the enabled engine(s), uploads results to S3, then exits.
 */
@Component
@ConditionalOnProperty(name = "benchmark.mode", havingValue = "worker", matchIfMissing = true)
public class BenchmarkWorkerRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkWorkerRunner.class);

    private final BenchmarkOrchestrator orchestrator;
    private final S3ResultStore s3Store;
    private final BenchmarkProperties props;

    public BenchmarkWorkerRunner(BenchmarkOrchestrator orchestrator,
                                 S3ResultStore s3Store,
                                 BenchmarkProperties props) {
        this.orchestrator = orchestrator;
        this.s3Store      = s3Store;
        this.props        = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String bucket = props.getS3().getBucket();
        String runId  = props.getS3().getRunId();

        if (bucket.isBlank() || runId.isBlank()) {
            log.info("S3 bucket/runId not configured — skipping auto-run (local mode)");
            return;
        }

        log.info("Worker mode: running benchmark (rowCount={}, readIter={})",
                 props.getRowCount(), props.getReadIter());

        List<BenchmarkResult> results =
            orchestrator.runAll(null, props.getRowCount(), props.getReadIter());

        log.info("Benchmark complete — uploading {} results to S3", results.size());
        s3Store.upload(results);

        log.info("Done. Exiting.");
        System.exit(0);
    }
}