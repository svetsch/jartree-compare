package io.jartree.compare;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects where the time of a comparison is spent. Phases running on several worker threads are summed over
 * the threads, so their total can exceed the elapsed (wall clock) time.
 */
public final class Timings {

    public enum Phase {
        SCAN("Scan archives (read, uncompress, hash)"),
        MATCH("Pair libraries"),
        UNCOMPRESS("Uncompress changed libraries"),
        COMPARE("Compare entries and bytecode"),
        DECOMPILE("Decompile"),
        DECOMPILE_WAIT("Wait for the decompiler"),
        DIFF("Diff decompiled sources"),
        CACHE("Cache read / write");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Time and number of measurements of one phase. */
    public record Entry(Phase phase, long millis, long count) {
    }

    /** One library and how long its comparison took. */
    public record LibraryTime(String library, long millis, boolean cached) {
    }

    /** Immutable result: phases, elapsed time and the slowest libraries. */
    public record Summary(List<Entry> phases, long elapsedMillis, List<LibraryTime> slowestLibraries) {

        public static final Summary NONE = new Summary(List.of(), 0, List.of());

        public Summary {
            phases = List.copyOf(phases);
            slowestLibraries = List.copyOf(slowestLibraries);
        }

        public long millis(Phase phase) {
            return phases.stream().filter(e -> e.phase() == phase).mapToLong(Entry::millis).sum();
        }

        /** One line such as "scan 1.2 s | decompile 14.9 s | ... | elapsed 18.2 s" (ASCII, for any console). */
        public String line() {
            List<String> parts = new ArrayList<>();
            for (Entry e : phases) {
                if (e.millis() > 0 || e.phase() == Phase.DECOMPILE) {
                    parts.add(e.phase().name().toLowerCase().replace('_', ' ') + " " + format(e.millis()));
                }
            }
            parts.add("elapsed " + format(elapsedMillis));
            return String.join(" | ", parts);
        }
    }

    private final Map<Phase, LongAdder> nanos = new EnumMap<>(Phase.class);
    private final Map<Phase, LongAdder> counts = new EnumMap<>(Phase.class);
    private final List<LibraryTime> libraries = new ArrayList<>();

    public Timings() {
        for (Phase p : Phase.values()) {
            nanos.put(p, new LongAdder());
            counts.put(p, new LongAdder());
        }
    }

    /** Adds the time elapsed since {@code startNanos} (from {@link System#nanoTime()}) to a phase. */
    public void since(Phase phase, long startNanos) {
        nanos.get(phase).add(System.nanoTime() - startNanos);
        counts.get(phase).increment();
    }

    void library(String path, long millis, boolean cached) {
        synchronized (libraries) {
            libraries.add(new LibraryTime(path, millis, cached));
        }
    }

    public Summary summary(long elapsedMillis) {
        List<Entry> entries = new ArrayList<>();
        for (Phase p : Phase.values()) {
            entries.add(new Entry(p, nanos.get(p).sum() / 1_000_000, counts.get(p).sum()));
        }
        List<LibraryTime> slowest;
        synchronized (libraries) {
            slowest = libraries.stream()
                    .sorted((a, b) -> Long.compare(b.millis(), a.millis()))
                    .limit(10)
                    .toList();
        }
        return new Summary(entries, elapsedMillis, slowest);
    }

    public static String format(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        if (millis < 60_000) {
            return String.format("%.1f s", millis / 1000.0);
        }
        return String.format("%d min %02d s", millis / 60_000, (millis / 1000) % 60);
    }
}
