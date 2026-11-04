package com.crosscert.passkey.admin.web.support;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/**
 * "불러온 행 전부"를 내보내는 CSV(감사 로그 탭·활동)가 서버에서 다시 읽는 쪽 수의 안전 상한.
 *
 * <p>화면의 "더 보기"는 상한이 없다(원본처럼 쪽을 계속 붙인다). 내보내기는 화면이 불러온 범위를 서버가 쪽 단위로 다시 읽으므로
 * 넉넉한 상한(50건 × 400쪽 = 20,000건)을 두고, 넘으면 일부만 담은 파일을 주지 않고 이유를 알리는 오류로 답한다.
 * 응답은 422 + JSON {@code {"message": …}} — 공통 다운로드 도우미(app.js csvDownload)가 이 문구를 토스트에 싣는다.
 */
public final class ExportLimit {

    /** 내보내기가 다시 읽는 최대 쪽 수. */
    public static final int MAX_PAGES = 400;

    public static final String MESSAGE = "내보낼 행이 너무 많습니다(최대 20,000건). 범위를 좁혀 다시 시도하세요.";

    private ExportLimit() {}

    /** 상한을 넘었을 때의 응답(422 + JSON message). */
    public static ResponseEntity<byte[]> tooLarge() {
        String body = "{\"message\":\"" + MESSAGE + "\"}";
        return ResponseEntity.unprocessableEntity()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
