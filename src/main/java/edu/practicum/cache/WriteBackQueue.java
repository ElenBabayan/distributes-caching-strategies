package edu.practicum.cache;

import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Async flush queue for write-back policy.
 * Writes are acknowledged to the caller after the cache write; this queue
 * drains asynchronously to the backing store on a dedicated thread.
 *
 * Tracks queue depth (indicator of durability exposure) and total flush lag.
 */
public class WriteBackQueue implements AutoCloseable {

    private record Entry(String key, String value) {}

    private final BlockingQueue<Entry> queue = new LinkedBlockingQueue<>(100_000);
    private final BackingStore backingStore;
    private final ExecutorService flusher;
    private final LongAdder flushCount = new LongAdder();
    private volatile boolean running = true;

    public WriteBackQueue(BackingStore backingStore) {
        this.backingStore = backingStore;
        this.flusher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "write-back-flusher");
            t.setDaemon(true);
            return t;
        });
        flusher.submit(this::flushLoop);
    }

    public void enqueue(String key, String value) {
        // Best-effort enqueue; if queue is full, flush synchronously as a safety valve.
        if (!queue.offer(new Entry(key, value))) {
            backingStore.write(key, value);
        }
    }

    private void flushLoop() {
        while (running || !queue.isEmpty()) {
            try {
                Entry e = queue.poll(10, TimeUnit.MILLISECONDS);
                if (e != null) {
                    backingStore.write(e.key(), e.value());
                    flushCount.increment();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public int queueDepth() { return queue.size(); }
    public long flushCount() { return flushCount.sum(); }

    /** Drain all pending writes before closing. */
    public void drain() throws InterruptedException {
        while (!queue.isEmpty()) Thread.sleep(5);
    }

    @Override
    public void close() {
        running = false;
        flusher.shutdown();
        try { flusher.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }
}
