package edu.practicum.cache;

public enum Topology {
    LOCAL("Local"),
    DISTRIBUTED("Distributed"),
    HYBRID("Hybrid");

    public final String label;
    Topology(String label) { this.label = label; }
}
