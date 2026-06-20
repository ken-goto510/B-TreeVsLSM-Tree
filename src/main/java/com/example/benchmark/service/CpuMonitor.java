package com.example.benchmark.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Measures CPU usage during a benchmark task.
 *
 * For a container (containerName != null) it reads the container's cumulative
 * CPU counters immediately before and after the task, then computes the average
 * CPU% over the exact workload window:
 *
 *     CPU% = (deltaContainerCpu / deltaSystemCpu) * numCpus * 100
 *
 * This reflects only the DB process inside the container — not the JVM running
 * the benchmark — and stays accurate even for short workloads (no polling /
 * sampling-window misalignment).
 *
 * The counter source is auto-detected:
 *   1. ECS Fargate/EC2 — the ECS Task Metadata Endpoint v4 ({@code /task/stats}),
 *      when the {@code ECS_CONTAINER_METADATA_URI_V4} env var is present.
 *      (Docker socket is unavailable on Fargate.)
 *   2. Local / plain Docker — the Docker Engine API via {@code /var/run/docker.sock}.
 *   3. Otherwise (or containerName == null, e.g. embedded RocksDB, or any failure)
 *      — JVM-process CPU (getProcessCpuLoad, sampled during the task).
 *
 * Both container sources return the same Docker {@code cpu_stats} JSON shape, so
 * the parsing and the CPU% formula are shared.
 */
public class CpuMonitor {

    private static final String DOCKER_SOCKET_PATH = "/var/run/docker.sock";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Set by ECS on every task container; null elsewhere. */
    private static final String ECS_METADATA_URI = System.getenv("ECS_CONTAINER_METADATA_URI_V4");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** containerName -> dockerId, resolved once from the ECS task metadata. */
    private static volatile Map<String, String> ecsNameToId;
    /** Task vCPU count (from ECS task metadata Limits.CPU), used if online_cpus is absent. */
    private static volatile int ecsTaskCpus = 0;

    private final String containerName;

    /** Measure the given Docker container's CPU. */
    public CpuMonitor(String containerName) {
        this.containerName = containerName;
    }

    /** Fallback: measure JVM process CPU. */
    public CpuMonitor() {
        this(null);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public double measureDuring(Runnable task) {
        if (containerName == null) {
            return measureJvm(task);
        }

        // Read counters before/after; run a JVM poller in parallel as fallback.
        long[] start = readCounters();
        JvmPoller poller = JvmPoller.start();
        try {
            task.run();
        } finally {
            poller.stop();
        }
        long[] end = readCounters();

        Double containerCpu = computeContainerCpu(start, end);
        return (containerCpu != null) ? containerCpu : poller.average();
    }

    // ── Container CPU counters ────────────────────────────────────────────────

    /** @return [totalCpuUsageNs, systemCpuUsageNs, onlineCpus] or null on failure */
    private long[] readCounters() {
        return (ECS_METADATA_URI != null) ? readCountersEcs() : readCountersDocker();
    }

    /** Parses a Docker {@code cpu_stats} node into [total, system, numCpus]; null if invalid. */
    private long[] parseCpuStats(JsonNode cpu, int fallbackCpus) {
        long total   = cpu.at("/cpu_usage/total_usage").asLong();
        long system  = cpu.at("/system_cpu_usage").asLong();
        int  numCpus = cpu.at("/online_cpus").asInt();
        if (numCpus <= 0) numCpus = fallbackCpus > 0 ? fallbackCpus
                                                      : Runtime.getRuntime().availableProcessors();
        if (total <= 0 || system <= 0) return null;
        return new long[]{ total, system, numCpus };
    }

    private Double computeContainerCpu(long[] start, long[] end) {
        if (start == null || end == null) return null;
        long deltaCpu = end[0] - start[0];
        long deltaSys = end[1] - start[1];
        int  numCpus  = (int) end[2];
        if (deltaSys <= 0 || deltaCpu < 0) return null;
        return ((double) deltaCpu / deltaSys) * numCpus * 100.0;
    }

    // ── ECS Task Metadata v4 source ───────────────────────────────────────────

    private long[] readCountersEcs() {
        try {
            String dockerId = resolveEcsDockerId(containerName);
            if (dockerId == null) return null;
            JsonNode stats = MAPPER.readTree(httpGet(ECS_METADATA_URI + "/task/stats"));
            JsonNode cpu = stats.path(dockerId).path("cpu_stats");
            if (cpu.isMissingNode() || cpu.isNull()) return null;
            return parseCpuStats(cpu, ecsTaskCpus);
        } catch (Exception e) {
            return null;
        }
    }

    /** Resolves a task-definition container name to its Docker ID via {@code /task} (cached). */
    private String resolveEcsDockerId(String name) throws IOException, InterruptedException {
        Map<String, String> map = ecsNameToId;
        if (map == null) {
            synchronized (CpuMonitor.class) {
                map = ecsNameToId;
                if (map == null) {
                    map = new HashMap<>();
                    JsonNode task = MAPPER.readTree(httpGet(ECS_METADATA_URI + "/task"));
                    for (JsonNode c : task.path("Containers")) {
                        String n  = c.path("Name").asText(null);
                        String id = c.path("DockerId").asText(null);
                        if (n != null && id != null) map.put(n, id);
                    }
                    // Task vCPU count: Limits.CPU is in whole vCPUs (e.g. 8.0).
                    ecsTaskCpus = (int) Math.round(task.at("/Limits/CPU").asDouble(0));
                    ecsNameToId = map;
                }
            }
        }
        return map.get(name);
    }

    private String httpGet(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    // ── Docker Engine API source (/var/run/docker.sock) ───────────────────────

    private long[] readCountersDocker() {
        try {
            String json = fetchFirstStatsFrame();
            JsonNode cpu = MAPPER.readTree(json).at("/cpu_stats");
            return parseCpuStats(cpu, 0);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads the first frame of {@code /stats?stream=true} and returns its body.
     * The first frame carries the live cumulative CPU counters immediately
     * (precpu is zeroed but unused here), so this returns without the ~1.5 s
     * delay that {@code stream=false} incurs.
     */
    private String fetchFirstStatsFrame() throws IOException {
        // HTTP/1.0 so the daemon streams raw JSON frames (no chunked encoding).
        String request = "GET /containers/" + containerName + "/stats?stream=true" +
                         " HTTP/1.0\r\nHost: localhost\r\n\r\n";

        Path sockPath = Path.of(DOCKER_SOCKET_PATH);
        if (sockPath.toFile().exists()) {
            UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(sockPath);
            try (SocketChannel ch = SocketChannel.open(addr)) {
                ch.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
                return readFirstJsonObject(ch);
            }
        }

        // Fallback: Docker Desktop TCP endpoint (enable in Docker Desktop settings).
        try (Socket sock = new Socket("127.0.0.1", 2375)) {
            sock.setSoTimeout(5000);
            sock.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            sock.getOutputStream().flush();
            return readFirstJsonObject(sock);
        }
    }

    private String readFirstJsonObject(SocketChannel ch) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ByteBuffer buf = ByteBuffer.allocate(8192);
        int n;
        while ((n = ch.read(buf)) != -1) {
            buf.flip();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            baos.write(data);
            buf.clear();
            String obj = extractFirstObject(baos.toString(StandardCharsets.UTF_8));
            if (obj != null) return obj;
        }
        return extractFirstObjectOrThrow(baos.toString(StandardCharsets.UTF_8));
    }

    private String readFirstJsonObject(Socket sock) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = sock.getInputStream().read(chunk)) != -1) {
            baos.write(chunk, 0, n);
            String obj = extractFirstObject(baos.toString(StandardCharsets.UTF_8));
            if (obj != null) return obj;
        }
        return extractFirstObjectOrThrow(baos.toString(StandardCharsets.UTF_8));
    }

    private String extractFirstObjectOrThrow(String raw) throws IOException {
        String obj = extractFirstObject(raw);
        if (obj == null) throw new IOException("No complete JSON object in Docker stats response");
        return obj;
    }

    /**
     * Returns the first balanced top-level {@code {...}} object found after the
     * HTTP headers, or null if not yet complete. Brace counting is string-aware
     * so braces inside string values don't break the balance.
     */
    private String extractFirstObject(String httpResponse) {
        int bodyStart = httpResponse.indexOf("\r\n\r\n");
        int from = (bodyStart >= 0) ? bodyStart + 4 : 0;
        int objStart = httpResponse.indexOf('{', from);
        if (objStart < 0) return null;

        int depth = 0;
        boolean inString = false, escaped = false;
        for (int i = objStart; i < httpResponse.length(); i++) {
            char c = httpResponse.charAt(i);
            if (inString) {
                if (escaped)      escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"')  inString = false;
            } else {
                if (c == '"')      inString = true;
                else if (c == '{') depth++;
                else if (c == '}') {
                    if (--depth == 0) return httpResponse.substring(objStart, i + 1);
                }
            }
        }
        return null; // incomplete — need more bytes
    }

    // ── JVM process CPU (fallback) ────────────────────────────────────────────

    private double measureJvm(Runnable task) {
        JvmPoller poller = JvmPoller.start();
        try {
            task.run();
        } finally {
            poller.stop();
        }
        return poller.average();
    }

    /** Samples JVM-process CPU load every second on a daemon thread. */
    private static final class JvmPoller {
        private final List<Double> samples = new CopyOnWriteArrayList<>();
        private final ScheduledExecutorService exec;

        private JvmPoller() {
            exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cpu-sampler");
                t.setDaemon(true);
                return t;
            });
        }

        static JvmPoller start() {
            JvmPoller p = new JvmPoller();
            p.exec.scheduleAtFixedRate(() -> {
                double v = processLoad();
                if (v >= 0) p.samples.add(v);
            }, 50, 1000, TimeUnit.MILLISECONDS);
            return p;
        }

        void stop() {
            exec.shutdown();
            try { exec.awaitTermination(500, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }

        double average() {
            return samples.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }

        private static double processLoad() {
            var bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                return sunBean.getProcessCpuLoad() * 100.0;
            }
            return -1;
        }
    }
}
