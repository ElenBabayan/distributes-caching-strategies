package edu.practicum.experiment;

import edu.practicum.benchmark.BenchmarkResult;
import edu.practicum.benchmark.WorkloadProfile;
import edu.practicum.cache.Topology;
import edu.practicum.cache.WritePolicy;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the full experiment suite:
 *   - 9 topology × write-policy configurations × 3 workloads = 27 latency benchmarks
 *   - Scalability sweep across replica counts for 3 topologies
 *
 * Results are written to results/latency.csv and results/scalability.csv
 * for use in the paper.
 */
public class ExperimentSuite {

    public void run() throws Exception {
        new java.io.File("results").mkdirs();

        System.out.println("=== LATENCY EXPERIMENTS ===");
        List<BenchmarkResult> latencyResults = runLatencyExperiments();
        writeLatencyCSV(latencyResults);  // write immediately in case scalability crashes

        System.out.println("\n=== SCALABILITY EXPERIMENT ===");
        List<ScalabilityExperiment.ScalabilityPoint> scalabilityResults = runScalabilityExperiment();

        writeScalabilityCSV(scalabilityResults);
        printSummary(latencyResults, scalabilityResults);
    }

    private List<BenchmarkResult> runLatencyExperiments() throws Exception {
        List<BenchmarkResult> results = new ArrayList<>();

        // Not all 9 combinations make equal sense for the paper;
        // we run all 9 for completeness but the paper focuses on the most informative 8.
        Topology[] topologies = {Topology.LOCAL, Topology.DISTRIBUTED, Topology.HYBRID};
        WritePolicy[] policies = {WritePolicy.CACHE_ASIDE, WritePolicy.WRITE_THROUGH, WritePolicy.WRITE_BACK};
        WorkloadProfile[] workloads = {WorkloadProfile.READ_HEAVY, WorkloadProfile.WRITE_HEAVY, WorkloadProfile.MIXED};

        int total = topologies.length * policies.length * workloads.length;
        int done = 0;

        for (Topology t : topologies) {
            for (WritePolicy p : policies) {
                for (WorkloadProfile w : workloads) {
                    System.out.printf("[%2d/%2d] %-12s × %-14s × %-22s ... ",
                            ++done, total, t.label, p.label, w.label);
                    System.out.flush();

                    BenchmarkResult r = new BenchmarkRunner(t, p, w).run();
                    results.add(r);

                    System.out.printf("r-p50=%.2fms r-p99=%.2fms w-p50=%.2fms w-p99=%.2fms hit=%.3f%n",
                            r.readP50Ns()/1e6, r.readP99Ns()/1e6,
                            r.writeP50Ns()/1e6, r.writeP99Ns()/1e6,
                            r.hitRatio());
                }
            }
        }
        return results;
    }

    private List<ScalabilityExperiment.ScalabilityPoint> runScalabilityExperiment() throws Exception {
        return new ScalabilityExperiment().run();
    }

    private void writeLatencyCSV(List<BenchmarkResult> results) throws Exception {
        try (PrintWriter pw = new PrintWriter(new FileWriter("results/latency.csv"))) {
            pw.println(BenchmarkResult.csvHeader());
            results.forEach(r -> pw.println(r.toCsv()));
        }
        System.out.println("\nLatency results written to results/latency.csv");
    }

    private void writeScalabilityCSV(List<ScalabilityExperiment.ScalabilityPoint> results) throws Exception {
        try (PrintWriter pw = new PrintWriter(new FileWriter("results/scalability.csv"))) {
            pw.println("topology,replicas,hit_ratio,throughput_ops_sec,p99_read_ms");
            for (var pt : results) {
                pw.printf("%s,%d,%.4f,%.1f,%.3f%n",
                        pt.topology(), pt.replicas(), pt.hitRatio(),
                        pt.throughputOpsPerSec(), pt.p99ReadNs() / 1e6);
            }
        }
        System.out.println("Scalability results written to results/scalability.csv");
    }

    private void printSummary(List<BenchmarkResult> latency,
                              List<ScalabilityExperiment.ScalabilityPoint> scalability) {
        System.out.println("\n=== LATENCY SUMMARY TABLE ===");
        System.out.printf("%-14s %-14s %-22s  %8s %8s %8s  %8s %8s %8s  %7s  %6s%n",
                "Topology", "WritePolicy", "Workload",
                "r-p50ms", "r-p95ms", "r-p99ms",
                "w-p50ms", "w-p95ms", "w-p99ms",
                "tps", "hit");
        System.out.println("-".repeat(130));
        latency.forEach(r -> System.out.printf(
                "%-14s %-14s %-22s  %8.3f %8.3f %8.3f  %8.3f %8.3f %8.3f  %7.0f  %6.3f%n",
                r.topology(), r.writePolicy(), r.workload(),
                r.readP50Ns()/1e6, r.readP95Ns()/1e6, r.readP99Ns()/1e6,
                r.writeP50Ns()/1e6, r.writeP95Ns()/1e6, r.writeP99Ns()/1e6,
                r.throughputOpsPerSec(), r.hitRatio()));

        System.out.println("\n=== SCALABILITY SUMMARY TABLE ===");
        System.out.printf("%-14s %8s %10s %12s %10s%n",
                "Topology", "Replicas", "HitRatio", "TPS", "p99-read-ms");
        System.out.println("-".repeat(60));
        scalability.forEach(pt -> System.out.printf("%-14s %8d %10.4f %12.1f %10.3f%n",
                pt.topology(), pt.replicas(), pt.hitRatio(),
                pt.throughputOpsPerSec(), pt.p99ReadNs() / 1e6));
    }
}
