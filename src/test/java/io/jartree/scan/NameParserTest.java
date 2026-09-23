package io.jartree.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class NameParserTest {

    @ParameterizedTest
    @CsvSource({
            "commons-lang3-3.12.0.jar, commons-lang3, 3.12.0, jar",
            "guava-31.1-jre.jar, guava, 31.1-jre, jar",
            "bcprov-jdk18on-1.78.jar, bcprov-jdk18on, 1.78, jar",
            "my-app-2.0.0-SNAPSHOT.war, my-app, 2.0.0-SNAPSHOT, war",
            "hibernate-core-5.6.15.Final.jar, hibernate-core, 5.6.15.Final, jar",
            "netty-4.1.100.Final, netty, 4.1.100.Final, ''",
            "foo_1.0.jar, foo, 1.0, jar",
    })
    void parsesVersionedNames(String file, String name, String version, String ext) {
        NameParser.ParsedName p = NameParser.parse(file);
        assertEquals(name, p.name());
        assertEquals(version, p.version());
        assertEquals(ext, p.extension());
    }

    @Test
    void namesWithoutVersion() {
        NameParser.ParsedName p = NameParser.parse("xml-apis-ext.jar");
        assertEquals("xml-apis-ext", p.name());
        assertNull(p.version());
        assertNull(NameParser.parse("foo-beta.jar").version());
    }

    @Test
    void normalizesVersionedDirectories() {
        assertEquals("modules/app.war/WEB-INF/lib/",
                NameParser.normalizePath("modules/app-1.2.war!/WEB-INF/lib/"));
        assertEquals("dist/*/lib/", NameParser.normalizePath("dist/1.2.3/lib/"));
        assertEquals("dist/product/lib/", NameParser.normalizePath("dist/product-4.5/lib/"));
    }
}
