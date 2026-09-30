package io.jartree.compare;

import java.util.List;

import io.jartree.bytecode.ClassAnalyzer.ApiDelta;
import io.jartree.bytecode.MemberChange;

/** A changed top-level class together with its inner classes. */
public final class ClassChange {

    /** Kind of difference found for a class. */
    public enum Nature {
        /** Decompiled source differs. */
        SOURCE,
        /** Only line numbers, local variable names or stack map frames differ. */
        DEBUG_INFO_ONLY,
        /** Bytecode differs but the decompiled source is identical (e.g. another compiler or constant order). */
        BYTECODE_ONLY,
        /** Bytecode differs and was not decompiled (disabled or over the limit). */
        NOT_DECOMPILED,
        /** Added or removed class. */
        PRESENCE
    }

    final String entryPrefix;
    final String internalName;
    final ChangeType type;
    final List<String> changedFiles;
    ApiDelta api = ApiDelta.EMPTY;
    List<MemberChange> members = List.of();
    Integer oldMajor;
    Integer newMajor;
    Nature nature = Nature.NOT_DECOMPILED;
    TextSupport.DiffText sourceDiff = TextSupport.DiffText.NONE;
    /** Gzip-compressed source diff with the whole class as context, or null. */
    byte[] wholeSourceDiff;
    TextSupport.DiffText bytecodeDiff = TextSupport.DiffText.NONE;
    PackedText oldSource;
    PackedText newSource;
    String decompileProblem;

    ClassChange(String entryPrefix, String internalName, ChangeType type, List<String> changedFiles) {
        this.entryPrefix = entryPrefix;
        this.internalName = internalName;
        this.type = type;
        this.changedFiles = List.copyOf(changedFiles);
    }

    /** Java class name, prefixed with the multi-release directory if any. */
    public String displayName() {
        return entryPrefix + internalName.replace('/', '.');
    }

    public String internalName() {
        return internalName;
    }

    /** {@code META-INF/versions/N/} for multi-release variants, otherwise empty. */
    public String entryPrefix() {
        return entryPrefix;
    }

    public ChangeType type() {
        return type;
    }

    /** Class file entries of this class (outer and inner) that were added, removed or modified. */
    public List<String> changedFiles() {
        return changedFiles;
    }

    public ApiDelta api() {
        return api;
    }

    /** Changed fields, methods, constructors and nested classes, with their location in the decompiled source. */
    public List<MemberChange> members() {
        return members;
    }

    public Integer oldMajor() {
        return oldMajor;
    }

    public Integer newMajor() {
        return newMajor;
    }

    public Nature nature() {
        return nature;
    }

    public TextSupport.DiffText sourceDiff() {
        return sourceDiff;
    }

    /**
     * The decompiled source diff with the whole class as context, or null when there is none: for classes whose
     * source did not change, for added and removed classes (their {@link #sourceDiff()} already shows the whole
     * class), and for reports written by earlier versions.
     */
    public String wholeSourceDiff() {
        return wholeSourceDiff == null ? null : TextSupport.decompress(wholeSourceDiff);
    }

    /** {@link #wholeSourceDiff()} as stored, gzip-compressed; do not modify. */
    public byte[] wholeSourceDiffCompressed() {
        return wholeSourceDiff;
    }

    public TextSupport.DiffText bytecodeDiff() {
        return bytecodeDiff;
    }

    /** Full decompiled source of the old version; null when the class did not exist or no source diff was made. */
    public PackedText oldSource() {
        return oldSource;
    }

    /** Full decompiled source of the new version; null when the class does not exist or no source diff was made. */
    public PackedText newSource() {
        return newSource;
    }

    /** Non-null if Vineflower reported problems while decompiling this class. */
    public String decompileProblem() {
        return decompileProblem;
    }

    /** False for changes that do not affect behavior as seen in the decompiled source. */
    public boolean isSignificant() {
        return nature != Nature.DEBUG_INFO_ONLY && nature != Nature.BYTECODE_ONLY;
    }
}
