package io.jartree.compare;

import java.util.List;

/**
 * Limits that were reached during a comparison, with the values needed to avoid them.
 *
 * @param maxClasses             the "max classes per library" limit that was used
 * @param librariesOverClassLimit number of libraries with more changed classes than the limit
 * @param skippedClasses         changed classes that were not decompiled because of the limit
 * @param maxClassesNeeded       limit needed to decompile every changed class (0 if the limit was not reached)
 * @param nestedDepth            the nested archive depth that was used
 * @param nestedDepthNeeded      depth needed to open every nested archive (0 if the limit was not reached)
 * @param unopenedArchives       nested archives that were not opened because of the depth limit
 */
public record Limits(int maxClasses, int librariesOverClassLimit, int skippedClasses, int maxClassesNeeded,
                     int nestedDepth, int nestedDepthNeeded, List<String> unopenedArchives) {

    public static final Limits NONE = new Limits(0, 0, 0, 0, 0, 0, List.of());

    public Limits {
        unopenedArchives = List.copyOf(unopenedArchives);
    }

    public boolean classLimitReached() {
        return maxClassesNeeded > 0;
    }

    public boolean depthLimitReached() {
        return nestedDepthNeeded > 0;
    }

    /** Human readable hints, one per limit reached, suitable for console output. */
    public List<String> hints() {
        List<String> hints = new java.util.ArrayList<>();
        if (classLimitReached()) {
            hints.add(librariesOverClassLimit + (librariesOverClassLimit == 1 ? " library" : " libraries")
                    + " reached the limit of " + maxClasses + " decompiled classes per library; " + skippedClasses
                    + " changed class(es) were not decompiled. Use --max-classes " + maxClassesNeeded
                    + " (or more) to decompile all of them.");
        }
        if (depthLimitReached()) {
            hints.add(unopenedArchives.size() + " nested archive(s) were not opened because of the nested archive "
                    + "depth limit of " + nestedDepth + " (e.g. " + unopenedArchives.get(0) + "). Use --nested-depth "
                    + nestedDepthNeeded + " (or more) to open all of them.");
        }
        return hints;
    }
}
