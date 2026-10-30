package com.careslot.prescribing.application;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class RxPdfTest {

    @Test
    void producesAPdfWhoseXrefOffsetsPointAtTheObjects() {
        byte[] pdf = RxPdf.render(List.of("Line one", "Paren (and) back\\slash", "Ünïcode → ascii"));
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(s).startsWith("%PDF-1.4").endsWith("%%EOF\n");
        assertThat(s).contains("(Paren \\(and\\) back\\\\slash)").contains("(?n?code ? ascii)");

        int startxref = Integer.parseInt(s.replaceAll("(?s).*startxref\\n(\\d+)\\n%%EOF\\n$", "$1"));
        assertThat(s.substring(startxref)).startsWith("xref");
        Matcher m = Pattern.compile("(\\d{10}) 00000 n").matcher(s.substring(startxref));
        int obj = 1;
        while (m.find()) {
            int off = Integer.parseInt(m.group(1));
            assertThat(s.substring(off)).as("object %d", obj).startsWith(obj + " 0 obj");
            obj++;
        }
        assertThat(obj - 1).isEqualTo(5);
    }
}
