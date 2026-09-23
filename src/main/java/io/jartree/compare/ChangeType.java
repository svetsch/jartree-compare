package io.jartree.compare;

/** Change type of a single entry (class or resource). */
public enum ChangeType {
    ADDED("+"), REMOVED("-"), MODIFIED("~");

    private final String symbol;

    ChangeType(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
