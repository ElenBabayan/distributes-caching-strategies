package edu.practicum.cache;

import java.util.Optional;

/**
 * Implements all 9 combinations of (Topology × WritePolicy).
 *
 * Topology determines which cache layers are present:
 *   LOCAL       → l1 only (Caffeine)
 *   DISTRIBUTED → l2 only (Redis)
 *   HYBRID      → l1 (Caffeine) + l2 (Redis)
 *
 * WritePolicy determines the consistency protocol on writes:
 *   CACHE_ASIDE   → backing store first, then invalidate cache
 *   WRITE_THROUGH → backing store + cache simultaneously (synchronous)
 *   WRITE_BACK    → cache first, async flush to backing store
 *
 * Hit ratio is tracked across all cache layers combined.
 */
public class CachePolicyExecutor implements AutoCloseable {

    private final Topology topology;
    private final WritePolicy writePolicy;
    private final CacheLayer l1;   // null for DISTRIBUTED
    private final CacheLayer l2;   // null for LOCAL
    private final BackingStore backingStore;
    private final WriteBackQueue writeBackQueue;

    public CachePolicyExecutor(Topology topology, WritePolicy writePolicy,
                                CacheLayer l1, CacheLayer l2,
                                BackingStore backingStore) {
        this.topology = topology;
        this.writePolicy = writePolicy;
        this.l1 = l1;
        this.l2 = l2;
        this.backingStore = backingStore;
        this.writeBackQueue = (writePolicy == WritePolicy.WRITE_BACK)
                ? new WriteBackQueue(backingStore) : null;
    }

    /**
     * Read path:
     *   1. Check L1 (if present). Hit → return.
     *   2. Check L2 (if present). Hit → populate L1, return.
     *   3. Backing store miss → populate all tiers, return.
     */
    public String get(String key) {
        if (l1 != null) {
            Optional<String> v1 = l1.get(key);
            if (v1.isPresent()) return v1.get();
        }
        if (l2 != null) {
            Optional<String> v2 = l2.get(key);
            if (v2.isPresent()) {
                if (l1 != null) l1.put(key, v2.get());
                return v2.get();
            }
        }
        // Cache miss — fetch from backing store and populate all tiers.
        String value = backingStore.read(key);
        if (l2 != null) l2.put(key, value);
        if (l1 != null) l1.put(key, value);
        return value;
    }

    /**
     * Write path depends on WritePolicy:
     *
     * CACHE_ASIDE:   Write backing store → invalidate cache tier(s).
     * WRITE_THROUGH: Write backing store + update all cache tiers synchronously.
     * WRITE_BACK:    Write cache tier(s) first → enqueue async flush to backing store.
     */
    public void put(String key, String value) {
        switch (writePolicy) {
            case CACHE_ASIDE -> {
                backingStore.write(key, value);
                if (l1 != null) l1.invalidate(key);
                if (l2 != null) l2.invalidate(key);
            }
            case WRITE_THROUGH -> {
                backingStore.write(key, value);
                if (l2 != null) l2.put(key, value);
                if (l1 != null) l1.put(key, value);
            }
            case WRITE_BACK -> {
                // Acknowledge after cache write only; backing store is async.
                if (l1 != null) l1.put(key, value);
                if (l2 != null) l2.put(key, value);
                writeBackQueue.enqueue(key, value);
            }
        }
    }

    /** Combined hit ratio across all cache layers (first-hit wins). */
    public double hitRatio() {
        long h = 0, m = 0;
        if (l1 != null) { h += l1.getHits(); m += l1.getMisses(); }
        // For HYBRID: l2 misses are already counted when l1 misses;
        // we only count l2 hits to avoid double-counting misses.
        if (l2 != null && l1 != null) { h += l2.getHits(); }
        else if (l2 != null) { h += l2.getHits(); m += l2.getMisses(); }
        return (h + m == 0) ? 0.0 : (double) h / (h + m);
    }

    public void resetStats() {
        if (l1 != null) l1.resetStats();
        if (l2 != null) l2.resetStats();
        backingStore.resetCounters();
    }

    public int writeBackQueueDepth() {
        return writeBackQueue != null ? writeBackQueue.queueDepth() : 0;
    }

    @Override
    public void close() throws Exception {
        if (writeBackQueue != null) {
            try { writeBackQueue.drain(); } catch (InterruptedException ignored) {}
            writeBackQueue.close();
        }
        if (l1 != null) l1.close();
        if (l2 != null) l2.close();
    }
}
