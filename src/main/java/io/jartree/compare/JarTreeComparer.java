package io.jartree.compare;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.jartree.decompile.Decompiler;
import io.jartree.scan.LibraryRef;
import io.jartree.scan.TreeScanner;

/** Entry point of the comparison: scan both trees, pair libraries, compare pairs in parallel. */
public final class JarTreeComparer {

    /** Receives progress messages; {@link #libraryDone} reports how many differing libraries were compared. */
    @FunctionalInterface
    public interface Progress {
        void message(String message);

        default void libraryDone(int done, int total) {
        }
    }

    private final CompareOptions options;
    private final Progress progress;
    private final ResultCache cache;

    public JarTreeComparer(CompareOptions options, Progress progress) {
        this(options, null, progress);
    }

    /** @param cache cache for library results and decompiled sources, or null to always compute everything */
    public JarTreeComparer(CompareOptions options, ResultCache cache, Progress progress) {
        this.options = options;
        this.cache = cache;
        this.progress = progress;
    }

    public ComparisonResult compare(Path oldRoot, Path newRoot) throws IOException {
        Instant started = Instant.now();
        Timings timings = new Timings();
        long scanStart = System.nanoTime();
        TreeScanner scanner = new TreeScanner(options.extensions(), options.classDirNames(), options.nestedDepth());
        progress.message("Scanning " + oldRoot);
        List<LibraryRef> oldAll = scanner.scan(oldRoot);
        progress.message("Scanning " + newRoot);
        List<LibraryRef> newAll = scanner.scan(newRoot);
        timings.since(Timings.Phase.SCAN, scanStart);
        List<LibraryRef> oldLibs = filter(oldAll);
        List<LibraryRef> newLibs = filter(newAll);
        List<String> warnings = new ArrayList<>(scanner.warnings());
        warnings.addAll(unmatchedPatternWarnings(oldAll, newAll));
        warnings.forEach(w -> progress.message("Warning: " + w));
        progress.message("Found " + oldLibs.size() + " old and " + newLibs.size() + " new libraries");

        long matchStart = System.nanoTime();
        List<LibraryMatcher.Pair> pairs = LibraryMatcher.match(oldLibs, newLibs);
        timings.since(Timings.Phase.MATCH, matchStart);
        Set<LibraryMatcher.Pair> differing = pairs.stream()
                .filter(p -> p.oldLib() != null && p.newLib() != null && !p.oldLib().sha256().equals(p.newLib().sha256()))
                .collect(Collectors.toSet());

        // entry comparison runs per library in parallel; decompilation is serialized and uses all threads itself
        int parallelLibraries = Math.max(1, Math.min(options.threads(), differing.size()));
        LibraryComparator comparator = new LibraryComparator(options,
                new Decompiler(options.threads(), options.maxSecondsPerMethod(), timings), cache, timings);

        List<LibraryDiff> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(parallelLibraries, r -> {
            Thread t = new Thread(r, "jartree-compare");
            t.setDaemon(true);
            return t;
        });
        try {
            AtomicInteger done = new AtomicInteger();
            List<Future<LibraryDiff>> futures = new ArrayList<>();
            for (LibraryMatcher.Pair pair : pairs) {
                if (!differing.contains(pair)) {
                    futures.add(CompletableFuture.completedFuture(comparator.compare(pair)));
                    continue;
                }
                futures.add(pool.submit(() -> {
                    LibraryDiff diff = comparator.compare(pair);
                    int count = done.incrementAndGet();
                    progress.message("[" + count + "/" + differing.size() + "] " + diff.status() + " "
                            + diff.primary().path() + (diff.fromCache() ? "  (cached)" : ""));
                    progress.libraryDone(count, differing.size());
                    return diff;
                }));
            }
            for (Future<LibraryDiff> f : futures) {
                results.add(f.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("Comparison failed", e.getCause());
        } finally {
            pool.shutdownNow();
        }

        Duration elapsed = Duration.between(started, Instant.now());
        return new ComparisonResult(oldRoot, newRoot, oldLibs.size(), newLibs.size(), results, warnings,
                started, elapsed, limits(results, scanner), timings.summary(elapsed.toMillis()));
    }

    private Limits limits(List<LibraryDiff> results, TreeScanner scanner) {
        int over = 0;
        int skipped = 0;
        int needed = 0;
        for (LibraryDiff d : results) {
            if (d.skippedClasses() > 0) {
                over++;
                skipped += d.skippedClasses();
                needed = Math.max(needed, options.maxClassesPerLibrary() + d.skippedClasses());
            }
        }
        return new Limits(options.maxClassesPerLibrary(), over, skipped, needed, options.nestedDepth(),
                scanner.nestedDepthNeeded(), scanner.unopenedArchives());
    }

    /** Include / exclude patterns apply to libraries; point out the ones that match none (e.g. entry paths). */
    private List<String> unmatchedPatternWarnings(List<LibraryRef> oldAll, List<LibraryRef> newAll) {
        List<String> warnings = new ArrayList<>();
        for (var entry : List.of(Map.entry("Include", options.includes()), Map.entry("Exclude", options.excludes()))) {
            for (String glob : entry.getValue()) {
                Pattern p = globToRegex(glob);
                boolean any = oldAll.stream().anyMatch(l -> matches(p, l)) || newAll.stream().anyMatch(l -> matches(p, l));
                if (!any) {
                    warnings.add(entry.getKey() + " pattern '" + glob + "' matched no library. Library patterns apply "
                            + "to library paths such as lib/foo-1.0.jar; to ignore files inside libraries (e.g. "
                            + "META-INF/MANIFEST.MF) use 'Ignore entries' (--ignore-entry).");
                }
            }
        }
        return warnings;
    }

    private List<LibraryRef> filter(List<LibraryRef> libs) {
        List<Pattern> includes = options.includes().stream().map(JarTreeComparer::globToRegex).toList();
        List<Pattern> excludes = options.excludes().stream().map(JarTreeComparer::globToRegex).toList();
        return libs.stream()
                .filter(l -> includes.isEmpty() || includes.stream().anyMatch(p -> matches(p, l)))
                .filter(l -> excludes.stream().noneMatch(p -> matches(p, l)))
                .toList();
    }

    private static boolean matches(Pattern p, LibraryRef lib) {
        return p.matcher(lib.path()).matches() || p.matcher(lib.fileName()).matches();
    }

    /** {@code **} matches across directories, {@code *} and {@code ?} within one path segment. */
    static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 2 < glob.length() && glob.charAt(i + 1) == '*' && glob.charAt(i + 2) == '/') {
                    // "**/" matches zero or more directories
                    sb.append("(?:.*/)?");
                    i += 2;
                } else if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i++;
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                sb.append("[^/]");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }
}
