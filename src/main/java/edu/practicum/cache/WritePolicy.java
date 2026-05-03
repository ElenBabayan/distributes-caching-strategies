package edu.practicum.cache;

public enum WritePolicy {
    CACHE_ASIDE("CacheAside"),
    WRITE_THROUGH("WriteThrough"),
    WRITE_BACK("WriteBack");

    public final String label;
    WritePolicy(String label) { this.label = label; }
}
