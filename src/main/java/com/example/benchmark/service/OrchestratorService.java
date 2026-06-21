package com.example.benchmark.service;

import com.example.benchmark.model.BenchmarkProperties;
import com.example.benchmark.model.BenchmarkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@ConditionalOnProperty(name = "benchmark.mode", havingValue = "orchestrator")
public class OrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);

    /** task-family-name → S3 worker key */
    private static final List<String[]> TASK_SEQUENCE = List.of(
        new String[]{"db-benchmark-postgres",    "postgres"},
        new String[]{"db-benchmark-percona",     "percona"},
        new String[]{"db-benchmark-cockroachdb", "cockroachdb"},
        new String[]{"db-benchmark-cassandra",   "cassandra"},
        new String[]{"db-benchmark-scylladb",    "scylladb"}
    );

    private final BenchmarkProperties props;
    private final S3ResultStore s3Store;
    private final EcsClient ecs;

    private volatile boolean running = false;
    private volatile String  phase   = "idle";
    private final List<BenchmarkResult> lastResults = new CopyOnWriteArrayList<>();

    public OrchestratorService(BenchmarkProperties props, S3ResultStore s3Store) {
        this.props   = props;
        this.s3Store = s3Store;
        this.ecs     = EcsClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(ContainerCredentialsProvider.builder().build())
                .build();
    }

    public boolean isRunning()             { return running; }
    public String  getPhase()              { return phase; }
    public List<BenchmarkResult> getLastResults() { return List.copyOf(lastResults); }

    public void startOrchestration(int rowCount, int readIter) {
        if (running) return;
        lastResults.clear();
        CompletableFuture.runAsync(() -> orchestrate(rowCount, readIter));
    }

    private void orchestrate(int rowCount, int readIter) {
        running = true;
        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        log.info("Starting orchestration runId={} rowCount={} readIter={}", runId, rowCount, readIter);

        try {
            for (String[] task : TASK_SEQUENCE) {
                String family    = task[0];
                String workerKey = task[1];
                phase = workerKey;
                log.info("[{}] Launching task {}", workerKey, family);

                String taskArn = launchTask(family, runId, workerKey, rowCount, readIter);
                waitUntilStopped(taskArn, workerKey);

                log.info("[{}] Task stopped — downloading results from S3", workerKey);
                List<BenchmarkResult> results = s3Store.download(runId, workerKey);
                lastResults.addAll(results);
                log.info("[{}] {} results collected", workerKey, results.size());
            }
        } catch (Exception e) {
            log.error("Orchestration failed at phase {}: {}", phase, e.getMessage(), e);
        } finally {
            running = false;
            phase   = "done";
            log.info("Orchestration complete — {} total results", lastResults.size());
        }
    }

    private String launchTask(String family, String runId, String workerKey,
                               int rowCount, int readIter) {
        BenchmarkProperties.EcsConfig ecs = props.getEcs();

        RunTaskResponse resp = this.ecs.runTask(r -> r
            .cluster(ecs.getCluster())
            .taskDefinition(family)
            .launchType(LaunchType.FARGATE)
            .networkConfiguration(nc -> nc
                .awsvpcConfiguration(vpc -> vpc
                    .subnets(Arrays.asList(ecs.getSubnets().split(",")))
                    .securityGroups(Arrays.asList(ecs.getSecurityGroups().split(",")))
                    .assignPublicIp(AssignPublicIp.ENABLED)))
            .overrides(o -> o
                .containerOverrides(co -> co
                    .name("benchmark-app")
                    .environment(
                        env("BENCHMARK_S3_RUN_ID",     runId),
                        env("BENCHMARK_S3_WORKER_KEY", workerKey),
                        env("BENCHMARK_ROW_COUNT",     String.valueOf(rowCount)),
                        env("BENCHMARK_READ_ITER",     String.valueOf(readIter))
                    ))));

        if (!resp.failures().isEmpty()) {
            throw new RuntimeException("ECS RunTask failed: " + resp.failures().get(0).reason());
        }
        String taskArn = resp.tasks().get(0).taskArn();
        log.info("[{}] Task launched: {}", workerKey, taskArn);
        return taskArn;
    }

    private void waitUntilStopped(String taskArn, String workerKey) throws InterruptedException {
        String cluster = props.getEcs().getCluster();
        while (true) {
            DescribeTasksResponse desc = ecs.describeTasks(r -> r
                .cluster(cluster).tasks(taskArn));
            String status = desc.tasks().get(0).lastStatus();
            log.debug("[{}] task status={}", workerKey, status);
            if ("STOPPED".equals(status)) break;
            Thread.sleep(20_000);
        }
    }

    private static KeyValuePair env(String name, String value) {
        return KeyValuePair.builder().name(name).value(value).build();
    }
}