package com.crosscert.passkey.admin.web.format

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

private val FORMULA_PREFIX: Pattern = Pattern.compile("^[=+\\-@\\t\\r].*", Pattern.DOTALL)
private val NEEDS_QUOTE: Pattern = Pattern.compile("[\",\\r\\n]")

/**
 * CSV 직렬화. RFC 4180 lite: 쉼표·따옴표·줄바꿈이 든 필드는 따옴표로 감싸고
 * 따옴표는 두 번 쓴다. Excel 수식 주입 방지로 `= + - @ TAB CR` 로 시작하면 앞에 작은따옴표를 붙인다.
 * Excel 의 UTF-8 인식을 위해 BOM 을 앞에 두고, 줄바꿈은 `\n`.
 * BOM 은 소스에 유니코드 이스케이프로만 쓴다(write 의 문자열) — CsvWriterTest 가 이 파일을 읽어 보이지 않는 실제 문자가 없는지 확인한다.
 */
object CsvWriter {

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

    /** internal: 같은 모듈 테스트(CsvWriterTest)가 부른다. */
    internal fun escape(v: String?): String {
        if (v == null) return ""
        var s: String = v
        if (FORMULA_PREFIX.matcher(s).matches()) s = "'$s"
        if (NEEDS_QUOTE.matcher(s).find()) return "\"" + s.replace("\"", "\"\"") + "\""
        return s
    }
}
