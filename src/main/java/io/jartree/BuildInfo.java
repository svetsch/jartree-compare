package io.jartree;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Version and git commit of this build, filled in by Maven from {@code build.properties}. Values are "unknown"
 * when the project was built without a git checkout (for example from a source archive).
 */
public final class BuildInfo {

    public static final String REPOSITORY = "https://github.com/svetsch/jartree-compare";

    public static final String VERSION = read("version");
    public static final String COMMIT = read("commit");
    public static final String COMMIT_FULL = read("commitFull");
    public static final String BRANCH = read("branch");
    public static final String COMMIT_TIME = read("commitTime");
    public static final String BUILD_TIME = read("buildTime");
    /** True when the build contained uncommitted changes. */
    public static final boolean DIRTY = Boolean.parseBoolean(read("dirty"));

    private BuildInfo() {
    }

    public static boolean hasCommit() {
        return !COMMIT.equals(UNKNOWN);
    }

    /** e.g. {@code 1.0.0 (commit a1b2c3d, built 2026-09-23 08:15)}. */
    public static String describe() {
        StringBuilder sb = new StringBuilder(VERSION);
        if (hasCommit()) {
            sb.append(" (commit ").append(COMMIT).append(DIRTY ? "-dirty" : "");
            if (!BUILD_TIME.equals(UNKNOWN)) {
                sb.append(", built ").append(BUILD_TIME);
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /** Link to this commit on GitHub, or the repository when the commit is unknown. */
    public static String commitUrl() {
        return hasCommit() ? REPOSITORY + "/commit/" + COMMIT_FULL : REPOSITORY;
    }

    private static final String UNKNOWN = "unknown";

    private static String read(String key) {
        Properties props = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("build.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            // fall through to the default below
        }
        String value = props.getProperty(key, "");
        // an unresolved Maven placeholder means the information was not available at build time
        return value.isBlank() || value.startsWith("${") ? UNKNOWN : value;
    }
}
