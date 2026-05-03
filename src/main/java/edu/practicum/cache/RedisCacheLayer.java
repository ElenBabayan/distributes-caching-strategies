package edu.practicum.cache;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Jedis;

import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * Distributed cache layer backed by Redis (localhost:6379, DB 2).
 * Uses a connection pool sized to the thread count of the benchmark.
 * Each operation incurs a real network round-trip over the loopback interface,
 * capturing actual Redis processing time and TCP stack overhead.
 */
public class RedisCacheLayer implements CacheLayer {

    private static final int REDIS_DB = 2;

    private final JedisPool pool;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public RedisCacheLayer(int poolSize) {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(poolSize + 4);
        cfg.setMaxIdle(poolSize);
        cfg.setMinIdle(2);
        cfg.setTestOnBorrow(false);
        this.pool = new JedisPool(cfg, "localhost", 6379, 2000);
        flush();
    }

    public void flush() {
        try (Jedis j = pool.getResource()) {
            j.select(REDIS_DB);
            j.flushDB();
        }
    }

    @Override
    public Optional<String> get(String key) {
        try (Jedis j = pool.getResource()) {
            j.select(REDIS_DB);
            String v = j.get(key);
            if (v != null) { hits.increment(); return Optional.of(v); }
            misses.increment();
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, String value) {
        try (Jedis j = pool.getResource()) {
            j.select(REDIS_DB);
            j.set(key, value);
        }
    }

    @Override
    public void invalidate(String key) {
        try (Jedis j = pool.getResource()) {
            j.select(REDIS_DB);
            j.del(key);
        }
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
        pool.close();
    }
}
