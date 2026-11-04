package com.crosscert.passkey.admin.web.support;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QrSvgTest {

    static final String URI = "otpauth://totp/Passkey%20Admin:alice%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Passkey%20Admin";

    @Test
    @DisplayName("qrIsInlineDataUriNoExternal: data:image/svg+xml;base64 로, 디코드한 SVG 에 외부 참조(href·url()·image·script) 없음")
    void qrIsInlineDataUriNoExternal() {
        String src = QrSvg.render(URI, 152);
        assertThat(src).startsWith("data:image/svg+xml;base64,");
        String svg = new String(Base64.getDecoder().decode(src.substring("data:image/svg+xml;base64,".length())), StandardCharsets.UTF_8);
        assertThat(svg).startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\"").contains("width=\"152\"").contains("height=\"152\"");
        // 이름공간 선언 외에는 주소가 없다.
        String rest = svg.replace("xmlns=\"http://www.w3.org/2000/svg\"", "");
        assertThat(rest).doesNotContain("http").doesNotContain("//").doesNotContain("href").doesNotContain("url(")
                .doesNotContain("<image").doesNotContain("<script").doesNotContain("<foreignObject");
        // otpauth 내용(secret)이 SVG 문자열로 새지 않는다(모듈 그림뿐).
        assertThat(svg).doesNotContain("JBSWY3DPEHPK3PXP").doesNotContain("otpauth");
    }

    @Test
    @DisplayName("그린 모듈을 다시 읽으면 같은 otpauth URI(여백 1모듈)")
    void decodesBack() throws Exception {
        String svg = QrSvg.svg(URI, 152);
        Matcher vb = Pattern.compile("viewBox=\"0 0 (\\d+) (\\d+)\"").matcher(svg);
        assertThat(vb.find()).isTrue();
        int total = Integer.parseInt(vb.group(1));
        int scale = 8;
        int quiet = 4; // 읽기용 여백(그림 밖)
        int w = (total + quiet * 2) * scale;
        int[] px = new int[w * w];
        java.util.Arrays.fill(px, 0xFFFFFFFF);
        Matcher m = Pattern.compile("M(\\d+) (\\d+)h1v1h-1z").matcher(svg);
        int modules = 0;
        while (m.find()) {
            int x = Integer.parseInt(m.group(1)), y = Integer.parseInt(m.group(2));
            assertThat(x).isGreaterThanOrEqualTo(1).isLessThan(total - 1);
            assertThat(y).isGreaterThanOrEqualTo(1).isLessThan(total - 1);
            modules++;
            for (int dy = 0; dy < scale; dy++) {
                for (int dx = 0; dx < scale; dx++) px[((y + quiet) * scale + dy) * w + (x + quiet) * scale + dx] = 0xFF000000;
            }
        }
        assertThat(modules).isGreaterThan(100);
        LuminanceSource src = new RGBLuminanceSource(w, w, px);
        Result r = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(src)));
        assertThat(r.getText()).isEqualTo(URI);
    }

    @Test
    @DisplayName("빈 값·잘못된 크기는 거절")
    void rejectsBadInput() {
        assertThatThrownBy(() -> QrSvg.render("", 152)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QrSvg.render(null, 152)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QrSvg.render(URI, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
