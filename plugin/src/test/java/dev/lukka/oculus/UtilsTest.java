package dev.lukka.oculus;

import dev.lukka.oculus.utils.Utils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UtilsTest {

    @Test
    void testColorizeNull() {
        assertEquals("", Utils.colorize(null));
    }

    @Test
    void testColorizeEmpty() {
        assertEquals("", Utils.colorize(""));
    }

    @Test
    void testColorizeLegacyCodes() {
        String input = "&aGreen &cRed &eYellow";
        String output = Utils.colorize(input);
        assertNotNull(output);
        assertFalse(output.contains("&a"));
        assertTrue(output.contains("Green"));
    }

    @Test
    void testColorizeHex() {
        String input = "#ff0000Red Text";
        String output = Utils.colorize(input);
        assertNotNull(output);
        assertTrue(output.contains("Red Text"));
    }
}
