package io.jartree.compare;

/**
 * A changed non-class entry.
 *
 * @param noise true if the change only affects build noise (volatile manifest attributes, generated comments) or
 *              signature files
 * @param diff  unified diff for text resources, empty for binary resources
 */
public record ResourceChange(String path, ChangeType type, boolean text, boolean noise, long oldSize, long newSize,
                             TextSupport.DiffText diff) {
}
