package io.jartree.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PackedTextTest {

    @Test
    void restoresTheTextExactly() {
        String text = "package a;\r\n\r\nclass Grüße { String s = \"€ 😀\"; }\n".repeat(200);
        PackedText packed = PackedText.of(text);
        assertEquals(text, packed.text());
        assertTrue(packed.encoded().length() < text.length() / 4, "repetitive text compresses well");
        assertEquals("", PackedText.of("").text());
    }

    @Test
    void roundTripsThroughItsEncodedForm() {
        PackedText packed = PackedText.of("timeout=30\n");
        PackedText back = PackedText.decode(packed.encoded());
        assertEquals(packed, back);
        assertEquals("timeout=30\n", back.text());
        assertNull(PackedText.decode(null));
    }

    @Test
    void skipsMissingAndOverlongTexts() {
        assertNull(PackedText.of(null));
        assertNull(PackedText.of("x".repeat(PackedText.MAX_LENGTH + 1)));
    }
}
