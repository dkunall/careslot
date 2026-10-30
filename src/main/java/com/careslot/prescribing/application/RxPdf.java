package com.careslot.prescribing.application;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A one-page PDF 1.4 written by hand: one Helvetica text block, no images, no fonts embedded. Enough for a
 * printable prescription without a PDF library; text outside printable ASCII is replaced with '?'.
 */
public final class RxPdf {

    private RxPdf() {
    }

    public static byte[] render(List<String> lines) {
        StringBuilder content = new StringBuilder("BT\n/F1 11 Tf\n14 TL\n50 790 Td\n");
        for (String line : lines) {
            content.append('(').append(escape(line)).append(") Tj T*\n");
        }
        content.append("ET\n");
        byte[] stream = content.toString().getBytes(StandardCharsets.US_ASCII);

        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> "
                        + "/Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n");
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        offsets.add(out.size());
        write(out, "5 0 obj\n<< /Length " + stream.length + " >>\nstream\n");
        out.writeBytes(stream);
        write(out, "endstream\nendobj\n");
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 6\n0000000000 65535 f \n");
        for (int off : offsets) {
            x.append(String.format("%010d 00000 n \n", off));
        }
        x.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        write(out, x.toString());
        return out.toByteArray();
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 32 || c > 126) {
                b.append('?');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }
}
