package edu.practicum.benchmark;

public enum WorkloadProfile {
    READ_HEAVY("Read-Heavy (90R/10W)", 0.90),
    WRITE_HEAVY("Write-Heavy (30R/70W)", 0.30),
    MIXED("Mixed (50R/50W)", 0.50);

    public final String label;
    public final double readFraction;

    WorkloadProfile(String label, double readFraction) {
        this.label = label;
        this.readFraction = readFraction;
    }
}
