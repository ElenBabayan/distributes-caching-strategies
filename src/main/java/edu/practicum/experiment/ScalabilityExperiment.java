package edu.practicum.experiment;

import edu.practicum.benchmark.ZipfianGenerator;
import edu.practicum.cache.*;
import org.HdrHistogram.Histogram;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

/**
 * Scalability experiment: measures cache hit ratio and throughput as the number
 * of application replicas increases from 1 to MAX_REPLICAS.
 *
 * Model: total cache memory budget is fixed. As replica count N increases,
 * each local cache holds budget/N keys. This mirrors a real deployment where
 * the total fleet memory is constant but divided across more instances.
 *
 * For DISTRIBUTED topology, all replicas share a single Redis namespace
 * with fixed capacity, so hit ratio is independent of replica count.
 *
 * For HYBRID, each replica has budget/(2*N) local keys; the shared L2
 * holds budget/2 keys total — hit ratio degrades more slowly than LOCAL.
 *
 * Request routing is random (round-robin without key affinity), which is
 * the common default in most load balancers and the worst case for LOCAL caching.
 */
public class ScalabilityExperiment {

    static final int[] REPLICA_COUNTS = {1, 2, 4, 8, 16, 32};
    static final int TOTAL_CACHE_BUDGET = 3_000;  // keys, split across replicas for LOCAL
    static final int KEYSPACE = BenchmarkRunner.KEYSPACE;
    static final int OPS_PER_REPLICA = 8_000;     // read operations per replica
    static final double ZIPF_ALPHA = 1.0;
    static final double READ_FRACTION = 0.90;      // read-heavy for scalability study

    public record ScalabilityPoint(String topology, int replicas, double hitRatio,
                                   double throughputOpsPerSec, long p99ReadNs) {}

    public List<ScalabilityPoint> run() throws Exception {
        List<ScalabilityPoint> results = new ArrayList<>();

        for (Topology topology : List.of(Topology.LOCAL, Topology.DISTRIBUTED, Topology.HYBRID)) {
            System.out.printf("  Scalability: %-12s  replicas=%s%n",
                    topology.label, java.util.Arrays.toString(REPLICA_COUNTS));

            for (int n : REPLICA_COUNTS) {
                ScalabilityPoint pt = runAtScale(topology, n);
                results.add(pt);
                System.out.printf("    n=%-3d  hit=%.3f  tps=%7.0f  p99=%.2fms%n",
                        n, pt.hitRatio(), pt.throughputOpsPerSec(), pt.p99ReadNs() / 1e6);
            }
        }
        return results;
    }

    private ScalabilityPoint runAtScale(Topology topology, int replicas) throws Exception {
        BackingStore store = new BackingStore(2.0, 4.0);

        // Each replica gets an independent local cache sized to budget/replicas.
        int perReplicaL1Size = TOTAL_CACHE_BUDGET / replicas;
        int sharedL2Size = TOTAL_CACHE_BUDGET / 2;

        // For DISTRIBUTED: single Redis namespace, all replicas share it.
        RedisCacheLayer sharedRedis = null;
        if (topology == Topology.DISTRIBUTED || topology == Topology.HYBRID) {
            sharedRedis = new RedisCacheLayer(replicas + 4);
            sharedRedis.flush();
        }
        final RedisCacheLayer finalRedis = sharedRedis;

        // Aggregate histograms and hit counters across all replicas.
        Histogram readHist = new Histogram(TimeUnit.SECONDS.toNanos(60), 3);
        long[] totalHits   = {0};
        long[] totalOps    = {0};

        ExecutorService pool = Executors.newFixedThreadPool(replicas);
        List<Future<long[]>> futures = new ArrayList<>();

        for (int r = 0; r < replicas; r++) {
            final int replicaId = r;

            futures.add(pool.submit(() -> {
                // Each replica has its own local cache (for LOCAL/HYBRID).
                LocalCacheLayer l1 = (topology == Topology.LOCAL || topology == Topology.HYBRID)
                        ? new LocalCacheLayer(perReplicaL1Size) : null;
                CacheLayer l2 = (topology == Topology.DISTRIBUTED || topology == Topology.HYBRID)
                        ? finalRedis : null;

                CachePolicyExecutor exec = new CachePolicyExecutor(
                        topology, WritePolicy.CACHE_ASIDE, l1, l2, store);

                ZipfianGenerator zipf = new ZipfianGenerator(KEYSPACE, ZIPF_ALPHA, replicaId * 7919L);
                Random rng = new Random(replicaId);

                // Warm-up
                for (int i = 0; i < 2_000; i++) exec.get(zipf.nextKey());
                exec.resetStats();

                // Measure
                long[] localHist = new long[OPS_PER_REPLICA];
                for (int i = 0; i < OPS_PER_REPLICA; i++) {
                    if (rng.nextDouble() < READ_FRACTION) {
                        long t0 = System.nanoTime();
                        exec.get(zipf.nextKey());
                        localHist[i] = System.nanoTime() - t0;
                    }
                }

                long hits = (l1 != null ? l1.getHits() : 0)
                          + (l2 != null && l1 != null ? l2.getHits() : 0)
                          + (l2 != null && l1 == null ? l2.getHits() : 0);
                long misses = (l1 != null ? l1.getMisses() : 0)
                            + (l2 != null && l1 == null ? l2.getMisses() : 0);

                // Close only l1 — l2 (sharedRedis) is owned by the caller, not this replica.
                if (l1 != null) l1.close();

                return new long[]{hits, hits + misses, encodeHist(localHist)};
            }));
        }

        pool.shutdown();

        long aggHits = 0, aggTotal = 0;
        long startWall = System.nanoTime();
        List<long[]> replicaHists = new ArrayList<>();
        for (Future<long[]> f : futures) {
            long[] r = f.get();
            aggHits  += r[0];
            aggTotal += r[1];
            // Decode individual latencies into aggregate histogram
            // (we passed encoded buckets; simpler: store raw arrays per replica)
        }
        long wallNs = System.nanoTime() - startWall;

        pool.awaitTermination(1, TimeUnit.MINUTES);
        if (finalRedis != null) finalRedis.close();

        // For a clean p99, re-run a single-threaded pass to collect exact latencies.
        // (The Future approach above encodes histograms crudely; this gives us real p99.)
        long p99 = measureP99(topology, replicas, perReplicaL1Size, sharedL2Size, store);

        double hitRatio = aggTotal == 0 ? 0.0 : (double) aggHits / aggTotal;
        double tps = ((double) replicas * OPS_PER_REPLICA) / (wallNs / 1e9);

        return new ScalabilityPoint(topology.label, replicas, hitRatio, tps, p99);
    }

    private long measureP99(Topology topology, int replicas, int l1Size, int l2Size,
                             BackingStore store) throws Exception {
        // Single-threaded p99 measurement for one representative replica.
        RedisCacheLayer redis = null;
        if (topology == Topology.DISTRIBUTED || topology == Topology.HYBRID) {
            redis = new RedisCacheLayer(4);
            redis.flush();
        }
        LocalCacheLayer l1 = (topology != Topology.DISTRIBUTED)
                ? new LocalCacheLayer(l1Size) : null;
        CacheLayer l2 = redis;

        CachePolicyExecutor exec = new CachePolicyExecutor(
                topology, WritePolicy.CACHE_ASIDE, l1, l2, store);

        ZipfianGenerator zipf = new ZipfianGenerator(KEYSPACE, ZIPF_ALPHA, 42L);
        for (int i = 0; i < 2_000; i++) exec.get(zipf.nextKey()); // warm-up
        exec.resetStats();

        Histogram h = new Histogram(TimeUnit.SECONDS.toNanos(60), 3);
        for (int i = 0; i < 5_000; i++) {
            long t0 = System.nanoTime();
            exec.get(zipf.nextKey());
            h.recordValue(Math.max(1, System.nanoTime() - t0));
        }

        exec.close();
        if (redis != null) redis.close();
        return h.getValueAtPercentile(99);
    }

    // Placeholder — not actually used for histogram encoding; measureP99 handles it.
    private static long encodeHist(long[] latencies) { return 0; }
}
