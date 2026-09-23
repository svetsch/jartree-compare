package io.jartree.compare;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

import io.jartree.compare.LibraryDiff.MatchedBy;
import io.jartree.scan.LibraryRef;
import io.jartree.scan.NameParser;

/**
 * Pairs the libraries of the old tree with those of the new tree. Pairing is attempted in this order:
 * identical path, same name ignoring versions (in the same, version-normalized, directory), same Maven
 * coordinates, identical content (moved/renamed file).
 */
public final class LibraryMatcher {

    public record Pair(LibraryRef oldLib, LibraryRef newLib, MatchedBy matchedBy) {
    }

    private LibraryMatcher() {
    }

    public static List<Pair> match(List<LibraryRef> oldLibs, List<LibraryRef> newLibs) {
        List<Pair> pairs = new ArrayList<>();
        List<LibraryRef> olds = new ArrayList<>(oldLibs);
        List<LibraryRef> news = new ArrayList<>(newLibs);

        matchBy(olds, news, LibraryRef::path, MatchedBy.PATH, pairs);
        matchBy(olds, news, LibraryRef::matchKey, MatchedBy.NAME, pairs);
        matchBy(olds, news, l -> l.mavenGa() == null ? null
                : NameParser.normalizePath(l.parentPath()) + l.mavenGa() + "." + l.extension(),
                MatchedBy.MAVEN_COORDINATES, pairs);
        matchBy(olds, news, LibraryRef::sha256, MatchedBy.CONTENT, pairs);

        olds.forEach(o -> pairs.add(new Pair(o, null, MatchedBy.NONE)));
        news.forEach(n -> pairs.add(new Pair(null, n, MatchedBy.NONE)));
        pairs.sort(Comparator.comparing(p -> (p.newLib() != null ? p.newLib() : p.oldLib()).path()));
        return pairs;
    }

    private static void matchBy(List<LibraryRef> olds, List<LibraryRef> news, Function<LibraryRef, String> key,
                                MatchedBy how, List<Pair> pairs) {
        Map<String, List<LibraryRef>> oldByKey = group(olds, key);
        Map<String, List<LibraryRef>> newByKey = group(news, key);
        for (var e : oldByKey.entrySet()) {
            List<LibraryRef> candidatesNew = newByKey.get(e.getKey());
            if (candidatesNew == null) {
                continue;
            }
            List<LibraryRef> candidatesOld = new ArrayList<>(e.getValue());
            candidatesNew = new ArrayList<>(candidatesNew);
            // identical content first, then same version, then pair the remaining ones in version order
            pairWhere(candidatesOld, candidatesNew, (o, n) -> o.sha256().equals(n.sha256()), how, pairs);
            pairWhere(candidatesOld, candidatesNew, (o, n) -> Objects.equals(o.version(), n.version()), how, pairs);
            candidatesOld.sort(Comparator.comparing(LibraryRef::version, VersionComparator.NULLS_FIRST));
            candidatesNew.sort(Comparator.comparing(LibraryRef::version, VersionComparator.NULLS_FIRST));
            int n = Math.min(candidatesOld.size(), candidatesNew.size());
            for (int i = 0; i < n; i++) {
                pairs.add(new Pair(candidatesOld.get(i), candidatesNew.get(i), how));
            }
            candidatesOld.subList(0, n).clear();
            candidatesNew.subList(0, n).clear();
        }
        Set<LibraryRef> paired = Collections.newSetFromMap(new IdentityHashMap<>());
        pairs.forEach(p -> {
            paired.add(p.oldLib());
            paired.add(p.newLib());
        });
        olds.removeIf(paired::contains);
        news.removeIf(paired::contains);
    }

    private static void pairWhere(List<LibraryRef> olds, List<LibraryRef> news,
                                  BiPredicate<LibraryRef, LibraryRef> predicate, MatchedBy how,
                                  List<Pair> pairs) {
        for (var it = olds.iterator(); it.hasNext(); ) {
            LibraryRef o = it.next();
            for (var jt = news.iterator(); jt.hasNext(); ) {
                LibraryRef n = jt.next();
                if (predicate.test(o, n)) {
                    pairs.add(new Pair(o, n, how));
                    it.remove();
                    jt.remove();
                    break;
                }
            }
        }
    }

    private static Map<String, List<LibraryRef>> group(List<LibraryRef> libs, Function<LibraryRef, String> key) {
        Map<String, List<LibraryRef>> map = new LinkedHashMap<>();
        for (LibraryRef l : libs) {
            String k = key.apply(l);
            if (k != null) {
                map.computeIfAbsent(k, x -> new ArrayList<>()).add(l);
            }
        }
        return map;
    }

    /** Orders versions like 1.9 &lt; 1.10 &lt; 2.0-SNAPSHOT &lt; 2.0. */
    static final class VersionComparator implements Comparator<String> {
        static final Comparator<String> NULLS_FIRST = Comparator.nullsFirst(new VersionComparator());

        @Override
        public int compare(String a, String b) {
            String[] pa = a.split("[.\\-_+~]");
            String[] pb = b.split("[.\\-_+~]");
            int n = Math.max(pa.length, pb.length);
            for (int i = 0; i < n; i++) {
                String x = i < pa.length ? pa[i] : "";
                String y = i < pb.length ? pb[i] : "";
                int c = compareToken(x, y);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        }

        private static int compareToken(String x, String y) {
            boolean xn = !x.isEmpty() && x.chars().allMatch(Character::isDigit);
            boolean yn = !y.isEmpty() && y.chars().allMatch(Character::isDigit);
            if (xn && yn) {
                return new BigInteger(x).compareTo(new BigInteger(y));
            }
            if (x.isEmpty() != y.isEmpty()) {
                // "1.0" > "1.0-SNAPSHOT" but "1.0.1" > "1.0"
                String other = x.isEmpty() ? y : x;
                boolean otherNumeric = other.chars().allMatch(Character::isDigit);
                int sign = x.isEmpty() ? 1 : -1;
                return otherNumeric ? -sign : sign;
            }
            if (xn != yn) {
                return xn ? 1 : -1;
            }
            return x.compareToIgnoreCase(y);
        }
    }
}
