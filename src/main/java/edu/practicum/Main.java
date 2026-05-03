package edu.practicum;

import edu.practicum.experiment.ExperimentSuite;

public class Main {
    public static void main(String[] args) throws Exception {
        System.out.println("Distributed Cache Benchmark Suite");
        System.out.println("Java " + System.getProperty("java.version") +
                " | Threads per config: " + edu.practicum.experiment.BenchmarkRunner.THREAD_COUNT +
                " | Ops per thread (measure): " + edu.practicum.experiment.BenchmarkRunner.MEASURE_OPS);
        System.out.println("Backing store: 2ms read / 4ms write (exponential distribution)");
        System.out.println("Keyspace: " + edu.practicum.experiment.BenchmarkRunner.KEYSPACE +
                " keys | Zipf α=" + edu.practicum.experiment.BenchmarkRunner.ZIPF_ALPHA);
        System.out.println();

        new ExperimentSuite().run();
    }
}
