package edu.practicum.benchmark;

import java.util.Random;

/**
 * Generates integer keys following a Zipfian distribution with skew α.
 * Uses the inverse-CDF method with a precomputed CDF table.
 * For α=1.0 and N=10000, the top 20% of keys account for ~80% of accesses.
 */
public class ZipfianGenerator {

    private final int keyspaceSize;
    private final double[] cdf;
    private final Random rng;

    public ZipfianGenerator(int keyspaceSize, double alpha, long seed) {
        this.keyspaceSize = keyspaceSize;
        this.rng = new Random(seed);

        // Precompute CDF: CDF[k] = sum_{i=1}^{k+1} (1/i^alpha) / H_N
        double harmonicN = 0.0;
        for (int i = 1; i <= keyspaceSize; i++) {
            harmonicN += 1.0 / Math.pow(i, alpha);
        }

        this.cdf = new double[keyspaceSize];
        double runningSum = 0.0;
        for (int i = 0; i < keyspaceSize; i++) {
            runningSum += 1.0 / Math.pow(i + 1, alpha);
            cdf[i] = runningSum / harmonicN;
        }
    }

    public ZipfianGenerator(int keyspaceSize, double alpha) {
        this(keyspaceSize, alpha, System.nanoTime());
    }

    /** Returns a key string "key:<k>" where k is Zipfian-distributed in [0, keyspaceSize). */
    public String nextKey() {
        double u = rng.nextDouble();
        int lo = 0, hi = keyspaceSize - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cdf[mid] < u) lo = mid + 1;
            else hi = mid;
        }
        return "key:" + lo;
    }

    public int getKeyspaceSize() {
        return keyspaceSize;
    }
}
