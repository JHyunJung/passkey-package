package com.crosscert.passkey.admin.web.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * CSV 직렬화(← admin-ui lib/csvExport.ts). RFC 4180 lite: 쉼표·따옴표·줄바꿈이 든 필드는 따옴표로 감싸고
 * 따옴표는 두 번 쓴다. Excel 수식 주입 방지로 {@code = + - @ TAB CR} 로 시작하면 앞에 작은따옴표를 붙인다.
 * Excel 의 UTF-8 인식을 위해 BOM 을 앞에 두고, 줄바꿈은 {@code \n}.
 */
public final class CsvWriter {

    private static final Pattern FORMULA_PREFIX = Pattern.compile("^[=+\\-@\\t\\r].*", Pattern.DOTALL);
    private static final Pattern NEEDS_QUOTE = Pattern.compile("[\",\\r\\n]");

    private CsvWriter() {}

    public static byte[] write(List<String> header, List<List<String>> rows) {
        List<String> lines = new ArrayList<>();
        lines.add(join(header));
        for (List<String> row : rows) lines.add(join(row));
        return ("\uFEFF" + String.join("\n", lines)).getBytes(StandardCharsets.UTF_8);
    }

    private static String join(List<String> fields) {
        List<String> out = new ArrayList<>(fields.size());
        for (String f : fields) out.add(escape(f));
        return String.join(",", out);
    }

    static String escape(String v) {
        if (v == null) return "";
        String s = v;
        if (FORMULA_PREFIX.matcher(s).matches()) s = "'" + s;
        if (NEEDS_QUOTE.matcher(s).find()) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
