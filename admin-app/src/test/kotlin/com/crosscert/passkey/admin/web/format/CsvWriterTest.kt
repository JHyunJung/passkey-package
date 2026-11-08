package com.crosscert.passkey.admin.web.format

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/** ← admin-ui/src/lib/csvExport.ts (원본에 vitest 없음 — 파일 머리 주석의 규칙을 확인) */
class CsvWriterTest {

    companion object {
        private fun text(b: ByteArray): String = String(b, StandardCharsets.UTF_8)
    }

    @Test
    @DisplayName("BOM + 헤더 + 행, 줄바꿈은 \\n")
    fun bomHeaderRowsAndNewlines() {
        val out = CsvWriter.write(listOf("이름", "값"), listOf(listOf("a", "1"), listOf("b", "2")))
        assertThat(out[0]).isEqualTo(0xEF.toByte())
        assertThat(out[1]).isEqualTo(0xBB.toByte())
        assertThat(out[2]).isEqualTo(0xBF.toByte())
        assertThat(text(out)).isEqualTo("﻿이름,값\na,1\nb,2")
    }

    @Test
    @DisplayName("쉼표·따옴표·줄바꿈 필드는 따옴표로 감싸고 따옴표는 두 번")
    fun quotesFieldsThatNeedIt() {
        val out = CsvWriter.write(listOf("h"), listOf(listOf("a,b"), listOf("say \"hi\""), listOf("x\ny"), listOf("x\ry")))
        assertThat(text(out)).isEqualTo("﻿h\n\"a,b\"\n\"say \"\"hi\"\"\"\n\"x\ny\"\n\"x\ry\"")
    }

    @Test
    @DisplayName("수식 주입 방지: = + - @ TAB CR 로 시작하면 작은따옴표 접두")
    fun neutralizesFormulaPrefixes() {
        assertThat(CsvWriter.escape("=SUM(A1)")).isEqualTo("'=SUM(A1)")
        assertThat(CsvWriter.escape("+1")).isEqualTo("'+1")
        assertThat(CsvWriter.escape("-1")).isEqualTo("'-1")
        assertThat(CsvWriter.escape("@x")).isEqualTo("'@x")
        assertThat(CsvWriter.escape("\tx")).isEqualTo("'\tx")
        assertThat(CsvWriter.escape("\rx")).isEqualTo("\"'\rx\"")
        assertThat(CsvWriter.escape("a=b")).isEqualTo("a=b")
    }

    @Test
    @DisplayName("BOM 은 소스에서도 \\uFEFF 이스케이프로 쓴다(보이지 않는 문자 방지)")
    fun bomIsWrittenAsEscapeInSource() {
        val src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/kotlin/com/crosscert/passkey/admin/web/format/CsvWriter.kt"))
        assertThat(src).contains("\\uFEFF").doesNotContain(if ("﻿" == "") "" else String(charArrayOf(0xFEFF.toChar())))
    }

    @Test
    @DisplayName("null 은 빈 칸")
    fun nullIsEmpty() {
        assertThat(text(CsvWriter.write(listOf("a", "b"), listOf(listOf(null, "x"))))).isEqualTo("﻿a,b\n,x")
    }
}
