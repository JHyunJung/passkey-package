package com.crosscert.passkey.admin.web.support

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.regex.Pattern

class QrSvgTest {

    companion object {
        const val URI = "otpauth://totp/Passkey%20Admin:alice%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Passkey%20Admin"
    }

    @Test
    @DisplayName("qrIsInlineDataUriNoExternal: data:image/svg+xml;base64 로, 디코드한 SVG 에 외부 참조(href·url()·image·script) 없음")
    fun qrIsInlineDataUriNoExternal() {
        val src = QrSvg.render(URI, 152)
        assertThat(src).startsWith("data:image/svg+xml;base64,")
        val svg = String(Base64.getDecoder().decode(src.substring("data:image/svg+xml;base64,".length)), StandardCharsets.UTF_8)
        assertThat(svg).startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\"").contains("width=\"152\"").contains("height=\"152\"")
        // 이름공간 선언 외에는 주소가 없다.
        val rest = svg.replace("xmlns=\"http://www.w3.org/2000/svg\"", "")
        assertThat(rest).doesNotContain("http").doesNotContain("//").doesNotContain("href").doesNotContain("url(")
            .doesNotContain("<image").doesNotContain("<script").doesNotContain("<foreignObject")
        // otpauth 내용(secret)이 SVG 문자열로 새지 않는다(모듈 그림뿐).
        assertThat(svg).doesNotContain("JBSWY3DPEHPK3PXP").doesNotContain("otpauth")
    }

    @Test
    @DisplayName("그린 모듈을 다시 읽으면 같은 otpauth URI(여백 1모듈)")
    fun decodesBack() {
        val svg = QrSvg.svg(URI, 152)
        val vb = Pattern.compile("viewBox=\"0 0 (\\d+) (\\d+)\"").matcher(svg)
        assertThat(vb.find()).isTrue()
        val total = Integer.parseInt(vb.group(1))
        val scale = 8
        val quiet = 4 // 읽기용 여백(그림 밖)
        val w = (total + quiet * 2) * scale
        val px = IntArray(w * w)
        java.util.Arrays.fill(px, 0xFFFFFFFF.toInt())
        val m = Pattern.compile("M(\\d+) (\\d+)h1v1h-1z").matcher(svg)
        var modules = 0
        while (m.find()) {
            val x = Integer.parseInt(m.group(1))
            val y = Integer.parseInt(m.group(2))
            assertThat(x).isGreaterThanOrEqualTo(1).isLessThan(total - 1)
            assertThat(y).isGreaterThanOrEqualTo(1).isLessThan(total - 1)
            modules++
            for (dy in 0 until scale) {
                for (dx in 0 until scale) px[((y + quiet) * scale + dy) * w + (x + quiet) * scale + dx] = 0xFF000000.toInt()
            }
        }
        assertThat(modules).isGreaterThan(100)
        val src: LuminanceSource = RGBLuminanceSource(w, w, px)
        val r = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src)))
        assertThat(r.text).isEqualTo(URI)
    }

    @Test
    @DisplayName("빈 값·잘못된 크기는 거절")
    fun rejectsBadInput() {
        assertThatThrownBy { QrSvg.render("", 152) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { QrSvg.render(null, 152) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { QrSvg.render(URI, 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
