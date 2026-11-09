package com.crosscert.passkey.admin.web.support

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.nio.charset.StandardCharsets
import java.util.Base64

private const val SVG_NS = "http://www.w3.org/2000/svg"
private const val MARGIN = 1

/**
 * QR 코드(← shell/QrCode.tsx 의 qrcode 캔버스). ZXing 으로 모듈 행렬을 만들어 SVG 문자열로 그리고,
 * `<img th:src>` 에 바로 넣을 수 있는 `data:image/svg+xml;base64,…` 로 돌려준다(th:utext·인라인 SVG 없이).
 *
 * SVG 에는 외부 참조가 없다(href·image·url() 없음). 독립 SVG 문서가 그려지려면 SVG 이름공간 선언
 * (`xmlns="http://www.w3.org/2000/svg"`)이 꼭 있어야 하는데, 이는 가져오는 주소가 아니라 이름공간 식별자다.
 * 여백은 원본(qrcode `margin: 1`)처럼 1모듈, 오류 정정 수준은 qrcode 라이브러리 기본값과 같은 M.
 */
object QrSvg {

    /** otpauth URI 를 sizePx × sizePx QR 이미지의 data URI 로. */
    fun render(otpauthUri: String?, sizePx: Int): String =
        "data:image/svg+xml;base64," +
            Base64.getEncoder().encodeToString(svg(otpauthUri, sizePx).toByteArray(StandardCharsets.UTF_8))

    /** SVG 문서 문자열(테스트·진단용). 원 Java 는 패키지 전용 — 같은 모듈 테스트(QrSvgTest)가 부르므로 internal. */
    internal fun svg(text: String?, sizePx: Int): String {
        if (text == null || text.isEmpty()) throw IllegalArgumentException("empty QR content")
        if (sizePx <= 0) throw IllegalArgumentException("size must be positive")
        val m: BitMatrix = try {
            QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                java.util.Map.of<EncodeHintType, Any>(
                    EncodeHintType.MARGIN, 0,
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name(),
                ),
            )
        } catch (e: WriterException) {
            throw IllegalStateException("QR encode failed", e)
        }
        val n = m.width
        val total = n + MARGIN * 2
        val path = StringBuilder()
        for (y in 0 until m.height) {
            for (x in 0 until n) {
                if (m.get(x, y)) path.append('M').append(x + MARGIN).append(' ').append(y + MARGIN).append("h1v1h-1z")
            }
        }
        return "<svg xmlns=\"" + SVG_NS + "\" width=\"" + sizePx + "\" height=\"" + sizePx + "\" viewBox=\"0 0 " + total + " " + total +
            "\" shape-rendering=\"crispEdges\">" +
            "<rect width=\"" + total + "\" height=\"" + total + "\" fill=\"#ffffff\"/>" +
            "<path fill=\"#000000\" d=\"" + path + "\"/>" +
            "</svg>"
    }
}
