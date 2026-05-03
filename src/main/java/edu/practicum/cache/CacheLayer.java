package edu.practicum.cache;

import java.util.Optional;

public interface CacheLayer extends AutoCloseable {
    Optional<String> get(String key);
    void put(String key, String value);
    void invalidate(String key);

    long getHits();
    long getMisses();

    default double hitRatio() {
        long h = getHits(), m = getMisses();
        return (h + m == 0) ? 0.0 : (double) h / (h + m);
    }

    void resetStats();

    @Override
    void close();
}
