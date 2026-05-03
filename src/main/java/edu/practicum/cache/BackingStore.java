package edu.practicum.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulated persistent backing store (e.g., a relational database).
 * Injects configurable latency to model realistic storage I/O.
 *
 * Read latency: Gaussian(readMeanMs, readMeanMs*0.3) ms, floored at 0.1ms.
 * Write latency: Gaussian(writeMeanMs, writeMeanMs*0.4) ms, floored at 0.1ms.
 *
 * Default values (2ms read / 4ms write) are calibrated to approximate a
 * co-located PostgreSQL instance under moderate load on an SSD-backed host,
 * consistent with latency distributions reported by Atikoglu et al. (2012)
 * for backing-store reads behind a Memcached deployment.
 */
public class BackingStore {

    private final ConcurrentHashMap<String, String> data = new ConcurrentHashMap<>();
    private final double readMeanNs;
    private final double writeMeanNs;

    private final AtomicInteger readCount = new AtomicInteger(0);
    private final AtomicInteger writeCount = new AtomicInteger(0);

    public BackingStore(double readMeanMs, double writeMeanMs) {
        this.readMeanNs = readMeanMs * 1_000_000.0;
        this.writeMeanNs = writeMeanMs * 1_000_000.0;
        // Pre-populate with representative values
        for (int i = 0; i < 10_000; i++) {
            data.put("key:" + i, "value:" + i);
        }
    }

    public String read(String key) {
        simulateLatency(readMeanNs);
        readCount.incrementAndGet();
        return data.getOrDefault(key, "value:" + key);
    }

    public void write(String key, String value) {
        simulateLatency(writeMeanNs);
        writeCount.incrementAndGet();
        data.put(key, value);
    }

    private void simulateLatency(double meanNs) {
        // Exponential distribution: more realistic than Gaussian for I/O latency tails.
        // E[X] = meanNs; use -meanNs * ln(U) where U ~ Uniform(0,1).
        double u = ThreadLocalRandom.current().nextDouble();
        if (u < 1e-10) u = 1e-10;
        long delayNs = (long) (-meanNs * Math.log(u));
        // Cap at 5× mean to avoid extreme outliers skewing results unnaturally.
        delayNs = Math.min(delayNs, (long) (meanNs * 5));

        long deadline = System.nanoTime() + delayNs;
        // Spin for sub-ms precision; sleep for longer waits to avoid wasting CPU.
        if (delayNs > 500_000) {
            long sleepMs = (delayNs - 200_000) / 1_000_000;
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        while (System.nanoTime() < deadline) Thread.onSpinWait();
    }

    public int getReadCount() { return readCount.get(); }
    public int getWriteCount() { return writeCount.get(); }
    public void resetCounters() { readCount.set(0); writeCount.set(0); }
}
