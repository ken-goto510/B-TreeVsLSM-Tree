package com.example.benchmark.service;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class CpuMonitor {

    public double measureDuring(Runnable task) {
        List<Double> samples = new CopyOnWriteArrayList<>();
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cpu-sampler");
            t.setDaemon(true);
            return t;
        });

        sampler.scheduleAtFixedRate(() -> {
            double v = processLoad();
            if (v >= 0) samples.add(v);
        }, 50, 100, TimeUnit.MILLISECONDS);

        try {
            task.run();
        } finally {
            sampler.shutdown();
            try { sampler.awaitTermination(500, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }

        return samples.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double processLoad() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            return sunBean.getProcessCpuLoad() * 100.0;
        }
        return -1;
    }
}
