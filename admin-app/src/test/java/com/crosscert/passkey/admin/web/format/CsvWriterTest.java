package com.crosscert.passkey.admin.web.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ← admin-ui/src/lib/csvExport.ts (원본에 vitest 없음 — 파일 머리 주석의 규칙을 확인) */
class CsvWriterTest {

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("BOM + 헤더 + 행, 줄바꿈은 \\n")
    void bomHeaderRowsAndNewlines() {
        byte[] out = CsvWriter.write(List.of("이름", "값"), List.of(List.of("a", "1"), List.of("b", "2")));
        assertThat(out[0]).isEqualTo((byte) 0xEF);
        assertThat(out[1]).isEqualTo((byte) 0xBB);
        assertThat(out[2]).isEqualTo((byte) 0xBF);
        assertThat(text(out)).isEqualTo("\uFEFF이름,값\na,1\nb,2");
    }

    @Test
    @DisplayName("쉼표·따옴표·줄바꿈 필드는 따옴표로 감싸고 따옴표는 두 번")
    void quotesFieldsThatNeedIt() {
        byte[] out = CsvWriter.write(List.of("h"), List.of(List.of("a,b"), List.of("say \"hi\""), List.of("x\ny"), List.of("x\ry")));
        assertThat(text(out)).isEqualTo("\uFEFFh\n\"a,b\"\n\"say \"\"hi\"\"\"\n\"x\ny\"\n\"x\ry\"");
    }

    @Test
    @DisplayName("수식 주입 방지: = + - @ TAB CR 로 시작하면 작은따옴표 접두")
    void neutralizesFormulaPrefixes() {
        assertThat(CsvWriter.escape("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(CsvWriter.escape("+1")).isEqualTo("'+1");
        assertThat(CsvWriter.escape("-1")).isEqualTo("'-1");
        assertThat(CsvWriter.escape("@x")).isEqualTo("'@x");
        assertThat(CsvWriter.escape("\tx")).isEqualTo("'\tx");
        assertThat(CsvWriter.escape("\rx")).isEqualTo("\"'\rx\"");
        assertThat(CsvWriter.escape("a=b")).isEqualTo("a=b");
    }

    @Test
    @DisplayName("BOM 은 소스에서도 \\uFEFF 이스케이프로 쓴다(보이지 않는 문자 방지)")
    void bomIsWrittenAsEscapeInSource() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/kotlin/com/crosscert/passkey/admin/web/format/CsvWriter.kt"));
        assertThat(src).contains("\\uFEFF").doesNotContain("\uFEFF".equals("") ? "" : new String(new char[]{(char) 0xFEFF}));
    }

    @Test
    @DisplayName("null 은 빈 칸")
    void nullIsEmpty() {
        assertThat(text(CsvWriter.write(List.of("a", "b"), List.of(Arrays.asList(null, "x"))))).isEqualTo("\uFEFFa,b\n,x");
    }
}
