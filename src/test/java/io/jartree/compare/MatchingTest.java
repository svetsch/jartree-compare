package io.jartree.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.jartree.scan.LibraryRef;
import io.jartree.scan.NameParser;

class MatchingTest {

    /** These tests only pair libraries; their content is never read. */
    private static final LibraryRef.ContentSource NO_CONTENT = new LibraryRef.ContentSource() {
        @Override
        public void forEach(io.jartree.scan.ZipUtil.EntryVisitor visitor) {
        }

        @Override
        public Map<String, byte[]> read(Set<String> names) {
            return Map.of();
        }

        @Override
        public LibraryRef.EntryReader reader() {
            return new LibraryRef.EntryReader() {
                @Override
                public byte[] read(String name) {
                    return null;
                }

                @Override
                public void close() {
                }
            };
        }
    };

    private static LibraryRef lib(String path, String sha) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        NameParser.ParsedName p = NameParser.parse(file);
        return new LibraryRef(path, LibraryRef.Kind.ARCHIVE, p.name(), p.version(), p.extension(), null, null,
                sha + "0".repeat(64 - sha.length()), 0, 0, 0, Set.of(), NO_CONTENT);
    }

    @Test
    void pairsByPathNameAndContent() {
        List<LibraryMatcher.Pair> pairs = LibraryMatcher.match(
                List.of(lib("lib/a-1.0.jar", "1"), lib("lib/b-1.0.jar", "2"), lib("lib/b-2.0.jar", "3"),
                        lib("lib/moved.jar", "4"), lib("lib/gone.jar", "5")),
                List.of(lib("lib/a-1.0.jar", "6"), lib("lib/b-2.1.jar", "7"), lib("lib/b-1.1.jar", "8"),
                        lib("other/renamed.jar", "4"), lib("lib/new.jar", "9")));
        Map<String, String> byOld = new java.util.TreeMap<>();
        pairs.forEach(p -> byOld.put(p.oldLib() == null ? "<none>:" + p.newLib().path() : p.oldLib().path(),
                (p.newLib() == null ? "<none>" : p.newLib().path()) + " " + p.matchedBy()));
        assertEquals("lib/a-1.0.jar PATH", byOld.get("lib/a-1.0.jar"));
        assertEquals("lib/b-1.1.jar NAME", byOld.get("lib/b-1.0.jar"));
        assertEquals("lib/b-2.1.jar NAME", byOld.get("lib/b-2.0.jar"));
        assertEquals("other/renamed.jar CONTENT", byOld.get("lib/moved.jar"));
        assertEquals("<none> NONE", byOld.get("lib/gone.jar"));
        assertEquals("lib/new.jar NONE", byOld.get("<none>:lib/new.jar"));
    }

    @Test
    void versionOrdering() {
        var c = new LibraryMatcher.VersionComparator();
        assertTrue(c.compare("1.9", "1.10") < 0);
        assertTrue(c.compare("2.0-SNAPSHOT", "2.0") < 0);
        assertTrue(c.compare("2.0", "2.0.1") < 0);
        assertEquals(0, c.compare("1.0", "1.0"));
    }

    @Test
    void globs() {
        assertTrue(JarTreeComparer.globToRegex("**/acme-*.jar").matcher("lib/x/acme-1.0.jar").matches());
        assertTrue(JarTreeComparer.globToRegex("**/acme-*.jar").matcher("acme-1.0.jar").matches());
        assertFalse(JarTreeComparer.globToRegex("**/acme-*.jar").matcher("lib/xacme-1.0.jar").matches());
        assertFalse(JarTreeComparer.globToRegex("lib/*.jar").matcher("lib/x/a.jar").matches());
        assertTrue(JarTreeComparer.globToRegex("app.war!/**").matcher("app.war!/WEB-INF/lib/a.jar").matches());
    }

    @Test
    void outerClassNames() {
        assertEquals("com/x/Foo", LibraryComparator.outerName("com/x/Foo$Bar$1"));
        assertEquals("com/x/Foo", LibraryComparator.outerName("com/x/Foo"));
        assertEquals("com/x/$Proxy", LibraryComparator.outerName("com/x/$Proxy"));
        assertEquals("Top", LibraryComparator.outerName("Top$1"));
    }
}
