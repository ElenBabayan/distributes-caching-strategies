package edu.practicum.experiment;

import edu.practicum.benchmark.BenchmarkResult;
import edu.practicum.benchmark.WorkloadProfile;
import edu.practicum.benchmark.ZipfianGenerator;
import edu.practicum.cache.*;
import org.HdrHistogram.Histogram;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

/**
 * Runs a single benchmark configuration (topology × write policy × workload).
 *
 * Protocol:
 *   1. Warm-up phase (WARMUP_OPS per thread, stats discarded): populates the cache
 *      to a steady-state hit ratio before measurement begins.
 *   2. Measurement phase (MEASURE_OPS per thread): records read and write latency
 *      using HDR histograms for precise p50/p95/p99 capture.
 *
 * Concurrency: THREAD_COUNT worker threads share one CachePolicyExecutor instance.
 * For LOCAL topology the executor holds a single Caffeine cache shared across threads
 * (this accurately models a single-replica scenario; the scalability experiment
 * separately varies per-replica cache sizes).
 */
public class BenchmarkRunner {

    public static final int THREAD_COUNT = 8;
    public static final int WARMUP_OPS   = 5_000;
    public static final int MEASURE_OPS  = 20_000;
    public static final int KEYSPACE     = 10_000;
    public static final double ZIPF_ALPHA = 1.0;

    private final Topology topology;
    private final WritePolicy writePolicy;
    private final WorkloadProfile workload;

    public BenchmarkRunner(Topology topology, WritePolicy writePolicy, WorkloadProfile workload) {
        this.topology = topology;
        this.writePolicy = writePolicy;
        this.workload = workload;
    }

    public BenchmarkResult run() throws Exception {
        BackingStore store = new BackingStore(2.0, 4.0); // 2ms read, 4ms write mean

        // Cache sizing: hot working set is ~20% of keyspace (Zipf α=1.0).
        // L1 sized to hold 30% of keyspace; L2 sized to hold 60%.
        int l1Size = (int) (KEYSPACE * 0.30);
        int l2Size = (int) (KEYSPACE * 0.60);

        CacheLayer l1 = switch (topology) {
            case LOCAL, HYBRID -> new LocalCacheLayer(l1Size);
            case DISTRIBUTED   -> null;
        };
        CacheLayer l2 = switch (topology) {
            case DISTRIBUTED, HYBRID -> new RedisCacheLayer(THREAD_COUNT + 4);
            case LOCAL               -> null;
        };

        // Flush Redis before each run to ensure clean state.
        if (l2 instanceof RedisCacheLayer rl) rl.flush();

        try (CachePolicyExecutor executor = new CachePolicyExecutor(topology, writePolicy, l1, l2, store)) {

            // --- Warm-up ---
            runPhase(executor, WARMUP_OPS, null, null);
            executor.resetStats();

            // --- Measurement ---
            Histogram readHist  = new Histogram(TimeUnit.SECONDS.toNanos(60), 3);
            Histogram writeHist = new Histogram(TimeUnit.SECONDS.toNanos(60), 3);

            long startNs = System.nanoTime();
            runPhase(executor, MEASURE_OPS, readHist, writeHist);
            long elapsedNs = System.nanoTime() - startNs;

            long totalOps = (long) THREAD_COUNT * MEASURE_OPS;
            double tps = totalOps / (elapsedNs / 1e9);

            return new BenchmarkResult(
                    topology.label, writePolicy.label, workload.label,
                    readHist.getValueAtPercentile(50),
                    readHist.getValueAtPercentile(95),
                    readHist.getValueAtPercentile(99),
                    writeHist.getValueAtPercentile(50),
                    writeHist.getValueAtPercentile(95),
                    writeHist.getValueAtPercentile(99),
                    tps,
                    executor.hitRatio(),
                    totalOps
            );
        }
    }

    private void runPhase(CachePolicyExecutor executor, int opsPerThread,
                          Histogram readHist, Histogram writeHist) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < THREAD_COUNT; t++) {
            final int threadId = t;
            futures.add(pool.submit(() -> {
                ZipfianGenerator zipf = new ZipfianGenerator(KEYSPACE, ZIPF_ALPHA, threadId * 31337L);
                Random rng = new Random(threadId);

                for (int i = 0; i < opsPerThread; i++) {
                    String key = zipf.nextKey();
                    boolean isRead = rng.nextDouble() < workload.readFraction;

                    long t0 = System.nanoTime();
                    if (isRead) {
                        executor.get(key);
                        if (readHist != null) {
                            long latency = Math.max(1, System.nanoTime() - t0);
                            synchronized (readHist) { readHist.recordValue(latency); }
                        }
                    } else {
                        executor.put(key, "v" + i);
                        if (writeHist != null) {
                            long latency = Math.max(1, System.nanoTime() - t0);
                            synchronized (writeHist) { writeHist.recordValue(latency); }
                        }
                    }
                }
            }));
        }

        pool.shutdown();
        for (Future<?> f : futures) f.get();
        pool.awaitTermination(5, TimeUnit.MINUTES);
    }
}
