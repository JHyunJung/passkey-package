package com.crosscert.passkey.admin.web.support;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * QR 코드(← shell/QrCode.tsx 의 qrcode 캔버스). ZXing 으로 모듈 행렬을 만들어 SVG 문자열로 그리고,
 * {@code <img th:src>} 에 바로 넣을 수 있는 {@code data:image/svg+xml;base64,…} 로 돌려준다(th:utext·인라인 SVG 없이).
 *
 * <p>SVG 에는 외부 참조가 없다(href·image·url() 없음). 독립 SVG 문서가 그려지려면 SVG 이름공간 선언
 * ({@code xmlns="http://www.w3.org/2000/svg"})이 꼭 있어야 하는데, 이는 가져오는 주소가 아니라 이름공간 식별자다.
 * 여백은 원본(qrcode {@code margin: 1})처럼 1모듈, 오류 정정 수준은 qrcode 라이브러리 기본값과 같은 M.
 */
public final class QrSvg {

    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final int MARGIN = 1;

    private QrSvg() {}

    /** otpauth URI 를 sizePx × sizePx QR 이미지의 data URI 로. */
    public static String render(String otpauthUri, int sizePx) {
        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg(otpauthUri, sizePx).getBytes(StandardCharsets.UTF_8));
    }

    /** SVG 문서 문자열(테스트·진단용). */
    static String svg(String text, int sizePx) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("empty QR content");
        if (sizePx <= 0) throw new IllegalArgumentException("size must be positive");
        BitMatrix m;
        try {
            m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.MARGIN, 0,
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name()));
        } catch (WriterException e) {
            throw new IllegalStateException("QR encode failed", e);
        }
        int n = m.getWidth();
        int total = n + MARGIN * 2;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < n; x++) {
                if (m.get(x, y)) path.append('M').append(x + MARGIN).append(' ').append(y + MARGIN).append("h1v1h-1z");
            }
        }
        return "<svg xmlns=\"" + SVG_NS + "\" width=\"" + sizePx + "\" height=\"" + sizePx + "\" viewBox=\"0 0 " + total + " " + total
                + "\" shape-rendering=\"crispEdges\">"
                + "<rect width=\"" + total + "\" height=\"" + total + "\" fill=\"#ffffff\"/>"
                + "<path fill=\"#000000\" d=\"" + path + "\"/>"
                + "</svg>";
    }
}
