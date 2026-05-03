package edu.practicum.benchmark;

public record BenchmarkResult(
        String topology,
        String writePolicy,
        String workload,
        long readP50Ns,
        long readP95Ns,
        long readP99Ns,
        long writeP50Ns,
        long writeP95Ns,
        long writeP99Ns,
        double throughputOpsPerSec,
        double hitRatio,
        long totalOps
) {
    public static String csvHeader() {
        return "topology,write_policy,workload," +
               "read_p50_ms,read_p95_ms,read_p99_ms," +
               "write_p50_ms,write_p95_ms,write_p99_ms," +
               "throughput_ops_sec,hit_ratio,total_ops";
    }

    public String toCsv() {
        return String.format("%s,%s,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.1f,%.4f,%d",
                topology, writePolicy, workload,
                readP50Ns / 1e6, readP95Ns / 1e6, readP99Ns / 1e6,
                writeP50Ns / 1e6, writeP95Ns / 1e6, writeP99Ns / 1e6,
                throughputOpsPerSec, hitRatio, totalOps);
    }

    public String toTable() {
        return String.format("  %-14s %-14s %-22s  r-p50=%5.2fms  r-p95=%5.2fms  r-p99=%6.2fms  " +
                             "w-p50=%5.2fms  w-p95=%5.2fms  w-p99=%6.2fms  " +
                             "tps=%7.0f  hit=%.3f",
                topology, writePolicy, workload,
                readP50Ns / 1e6, readP95Ns / 1e6, readP99Ns / 1e6,
                writeP50Ns / 1e6, writeP95Ns / 1e6, writeP99Ns / 1e6,
                throughputOpsPerSec, hitRatio);
    }
}
