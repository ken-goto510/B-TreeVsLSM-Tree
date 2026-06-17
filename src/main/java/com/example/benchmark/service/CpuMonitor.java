package com.example.benchmark.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

/**
 * Measures CPU usage during a benchmark task.
 *
 * For a Docker container (containerName != null) it reads the container's
 * cumulative CPU counters from the Docker Engine API (/var/run/docker.sock)
 * immediately before and after the task, then computes the average CPU% over
 * the exact workload window:
 *
 *     CPU% = (deltaContainerCpu / deltaSystemCpu) * numCpus * 100
 *
 * This reflects only the DB process inside the container — not the JVM running
 * the benchmark — and stays accurate even for short workloads (no polling /
 * sampling-window misalignment).
 *
 * Falls back to JVM-process CPU (getProcessCpuLoad, sampled during the task)
 * when containerName is null (embedded RocksDB) or the Docker socket is
 * unavailable.
 */
public class CpuMonitor {

    private static final String DOCKER_SOCKET_PATH = "/var/run/docker.sock";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    // ── Docker container CPU ──────────────────────────────────────────────────

    /** @return [totalCpuUsageNs, systemCpuUsageNs, onlineCpus] or null on failure */
    private long[] readCounters() {
        try {
            String json = fetchFirstStatsFrame();
            JsonNode cpu = MAPPER.readTree(json).at("/cpu_stats");
            long total   = cpu.at("/cpu_usage/total_usage").asLong();
            long system  = cpu.at("/system_cpu_usage").asLong();
            int  numCpus = cpu.at("/online_cpus").asInt();
            if (numCpus <= 0) numCpus = Runtime.getRuntime().availableProcessors();
            if (total <= 0 || system <= 0) return null;
            return new long[]{ total, system, numCpus };
        } catch (Exception e) {
            return null;
        }
    }

    private Double computeContainerCpu(long[] start, long[] end) {
        if (start == null || end == null) return null;
        long deltaCpu = end[0] - start[0];
        long deltaSys = end[1] - start[1];
        int  numCpus  = (int) end[2];
        if (deltaSys <= 0 || deltaCpu < 0) return null;
        return ((double) deltaCpu / deltaSys) * numCpus * 100.0;
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
