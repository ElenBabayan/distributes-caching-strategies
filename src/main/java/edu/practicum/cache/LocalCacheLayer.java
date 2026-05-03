package edu.practicum.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-process cache backed by Caffeine with Window-TinyLFU eviction policy.
 * All operations are bounded by JVM heap access time with no network overhead.
 */
public class LocalCacheLayer implements CacheLayer {

    private final Cache<String, String> cache;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public LocalCacheLayer(int maxSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .build();
    }

    @Override
    public Optional<String> get(String key) {
        String v = cache.getIfPresent(key);
        if (v != null) { hits.increment(); return Optional.of(v); }
        misses.increment();
        return Optional.empty();
    }

    @Override
    public void put(String key, String value) {
        cache.put(key, value);
    }

    @Override
    public void invalidate(String key) {
        cache.invalidate(key);
    }

    @Override public long getHits()   { return hits.sum(); }
    @Override public long getMisses() { return misses.sum(); }

    @Override
    public void resetStats() {
        hits.reset();
        misses.reset();
    }

    @Override
    public void close() {
        cache.cleanUp();
    }
}
