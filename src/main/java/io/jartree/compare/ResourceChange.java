package io.jartree.compare;

/**
 * A changed non-class entry.
 *
 * @param noise true if the change only affects build noise (volatile manifest attributes, generated comments) or
 *              signature files
 * @param diff    unified diff for text resources, empty for binary resources
 * @param oldText full old content of a text resource with a diff; null when absent, binary or too long
 * @param newText full new content, likewise
 */
public record ResourceChange(String path, ChangeType type, boolean text, boolean noise, long oldSize, long newSize,
                             TextSupport.DiffText diff, PackedText oldText, PackedText newText) {
}
