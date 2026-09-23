package io.jartree.compare;

/** Outcome of comparing one library of the old tree with its counterpart in the new tree. */
public enum LibraryStatus {
    /** Only present in the new tree. */
    ADDED,
    /** Only present in the old tree. */
    REMOVED,
    /** Code or resources differ. */
    CHANGED,
    /**
     * Archive bytes differ, but decompiled code and resources are equivalent: only timestamps, entry order, build
     * metadata, debug information or compiler specific bytecode changed.
     */
    REBUILT,
    /** Byte-identical. */
    UNCHANGED,
    /** Could not be compared. */
    ERROR
}
