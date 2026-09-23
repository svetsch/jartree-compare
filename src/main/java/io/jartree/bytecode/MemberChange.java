package io.jartree.bytecode;

/**
 * A changed field, method, constructor, initializer or nested class inside a changed class, optionally with its
 * location (1-based line range) in the old and new decompiled source.
 *
 * @param change      ADDED, REMOVED or MODIFIED
 * @param kind        what kind of member
 * @param owner       nested class path relative to the top-level class ("" for the top-level class, e.g. "Helper")
 * @param name        member name as written in source (class simple name for constructors, "static" for
 *                    static initializers)
 * @param declaration readable declaration, e.g. {@code public int add(int, int)}
 * @param detail      what changed, e.g. "body", "signature", "modifiers", "constant", "lambda"
 * @param paramCount  number of parameters for methods and constructors, -1 otherwise
 */
public record MemberChange(Change change, Kind kind, String owner, String name, String declaration, String detail,
                           int paramCount, Integer oldLine, Integer oldEnd, Integer newLine, Integer newEnd) {

    public enum Change { ADDED, REMOVED, MODIFIED }

    public enum Kind { FIELD, METHOD, CONSTRUCTOR, INITIALIZER, NESTED_CLASS }

    public MemberChange(Change change, Kind kind, String owner, String name, String declaration, String detail,
                        int paramCount) {
        this(change, kind, owner, name, declaration, detail, paramCount, null, null, null, null);
    }

    public MemberChange withOldLocation(Integer line, Integer end) {
        return new MemberChange(change, kind, owner, name, declaration, detail, paramCount, line, end, newLine, newEnd);
    }

    public MemberChange withNewLocation(Integer line, Integer end) {
        return new MemberChange(change, kind, owner, name, declaration, detail, paramCount, oldLine, oldEnd, line, end);
    }

    /** Declaration prefixed with the nested class, e.g. {@code [Helper] int apply(int)}. */
    public String label() {
        return owner.isEmpty() ? declaration : "[" + owner + "] " + declaration;
    }

    public boolean located() {
        return oldLine != null || newLine != null;
    }
}
