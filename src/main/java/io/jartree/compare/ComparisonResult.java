package io.jartree.compare;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Result of comparing two trees. */
public record ComparisonResult(Path oldRoot, Path newRoot, int oldLibraryCount, int newLibraryCount,
                               List<LibraryDiff> libraries, List<String> warnings, Instant started,
                               Duration duration, Limits limits, Timings.Summary timings) {

    public ComparisonResult {
        limits = limits == null ? Limits.NONE : limits;
        timings = timings == null ? Timings.Summary.NONE : timings;
    }

    public Map<LibraryStatus, Integer> countsByStatus() {
        Map<LibraryStatus, Integer> counts = new EnumMap<>(LibraryStatus.class);
        for (LibraryStatus s : LibraryStatus.values()) {
            counts.put(s, 0);
        }
        libraries.forEach(l -> counts.merge(l.status(), 1, Integer::sum));
        return counts;
    }

    /** True if any library was added, removed, changed or could not be compared. */
    public boolean hasDifferences() {
        return libraries.stream().anyMatch(l -> l.status() != LibraryStatus.UNCHANGED
                && l.status() != LibraryStatus.REBUILT);
    }
}
