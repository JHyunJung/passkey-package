package com.crosscert.passkey.admin.web.format

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

private val FORMULA_PREFIX: Pattern = Pattern.compile("^[=+\\-@\\t\\r].*", Pattern.DOTALL)
private val NEEDS_QUOTE: Pattern = Pattern.compile("[\",\\r\\n]")

/**
 * CSV 직렬화(← admin-ui lib/csvExport.ts). RFC 4180 lite: 쉼표·따옴표·줄바꿈이 든 필드는 따옴표로 감싸고
 * 따옴표는 두 번 쓴다. Excel 수식 주입 방지로 `= + - @ TAB CR` 로 시작하면 앞에 작은따옴표를 붙인다.
 * Excel 의 UTF-8 인식을 위해 BOM 을 앞에 두고, 줄바꿈은 `\n`.
 */
object CsvWriter {

    @JvmStatic
    fun write(header: List<String?>, rows: List<List<String?>>): ByteArray {
        val lines = ArrayList<String>()
        lines.add(join(header))
        for (row in rows) lines.add(join(row))
        return ("\uFEFF" + lines.joinToString("\n")).toByteArray(StandardCharsets.UTF_8)
    }

    private fun join(fields: List<String?>): String {
        val out = ArrayList<String>(fields.size)
        for (f in fields) out.add(escape(f))
        return out.joinToString(",")
    }

    /** 원 Java 는 패키지 전용 — 같은 패키지의 Java 테스트(CsvWriterTest)가 부르므로 public. */
    @JvmStatic
    fun escape(v: String?): String {
        if (v == null) return ""
        var s: String = v
        if (FORMULA_PREFIX.matcher(s).matches()) s = "'$s"
        if (NEEDS_QUOTE.matcher(s).find()) return "\"" + s.replace("\"", "\"\"") + "\""
        return s
    }
}
