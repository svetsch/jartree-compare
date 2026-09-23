package io.jartree.report;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import io.jartree.compare.ClassChange;
import io.jartree.compare.ComparisonResult;
import io.jartree.compare.LibraryDiff;
import io.jartree.compare.ResourceChange;

/** Writes all decompiled source and text resource diffs as a single unified diff file. */
public final class PatchReport {

    private final boolean includeNoise;
    private final boolean includeBytecode;

    public PatchReport(boolean includeNoise, boolean includeBytecode) {
        this.includeNoise = includeNoise;
        this.includeBytecode = includeBytecode;
    }

    public void write(ComparisonResult result, Path file) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (LibraryDiff lib : result.libraries()) {
                for (ClassChange cc : lib.classes()) {
                    if (!includeNoise && !cc.isSignificant()) {
                        continue;
                    }
                    w.write(cc.sourceDiff().text());
                    if (includeBytecode || cc.sourceDiff().isEmpty()) {
                        w.write(cc.bytecodeDiff().text());
                    }
                }
                for (ResourceChange rc : lib.resources()) {
                    if (includeNoise || !rc.noise()) {
                        w.write(rc.diff().text());
                    }
                }
            }
        }
    }
}
