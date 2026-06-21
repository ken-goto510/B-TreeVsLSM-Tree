package com.example.benchmark.service;

import com.example.benchmark.model.BenchmarkProperties;
import com.example.benchmark.model.BenchmarkResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.List;

@Component
public class S3ResultStore {

    private static final Logger log = LoggerFactory.getLogger(S3ResultStore.class);

    private final BenchmarkProperties props;
    private final ObjectMapper mapper;
    private final S3Client s3;

    public S3ResultStore(BenchmarkProperties props, ObjectMapper mapper) {
        this.props  = props;
        this.mapper = mapper;
        this.s3     = S3Client.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(ContainerCredentialsProvider.builder().build())
                .build();
    }

    /** Worker calls this: uploads its results under results/{runId}/{workerKey}.json */
    public void upload(List<BenchmarkResult> results) throws Exception {
        String key = resultKey(props.getS3().getRunId(), props.getS3().getWorkerKey());
        String json = mapper.writeValueAsString(results);
        s3.putObject(
            PutObjectRequest.builder().bucket(props.getS3().getBucket()).key(key).build(),
            RequestBody.fromString(json)
        );
        log.info("Uploaded {} results to s3://{}/{}", results.size(), props.getS3().getBucket(), key);
    }

    /** Orchestrator calls this: downloads results for one worker after its task stops. */
    public List<BenchmarkResult> download(String runId, String workerKey) throws Exception {
        String key = resultKey(runId, workerKey);
        log.info("Downloading s3://{}/{}", props.getS3().getBucket(), key);
        byte[] bytes = s3.getObject(
            GetObjectRequest.builder().bucket(props.getS3().getBucket()).key(key).build(),
            ResponseTransformer.toBytes()
        ).asByteArray();
        return mapper.readValue(bytes, new TypeReference<List<BenchmarkResult>>() {});
    }

    private static String resultKey(String runId, String workerKey) {
        return "results/" + runId + "/" + workerKey + ".json";
    }
}