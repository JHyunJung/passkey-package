package com.crosscert.passkey.webauthn.mds;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.metadata.data.MetadataBLOB;
import com.webauthn4j.metadata.data.MetadataBLOBFactory;
import com.webauthn4j.metadata.data.MetadataBLOBPayload;
import com.webauthn4j.metadata.data.MetadataBLOBPayloadEntry;
import com.webauthn4j.metadata.data.toc.StatusReport;

import java.util.ArrayList;
import java.util.List;

/**
 * webauthn4j-metadata로 동일 rawJwt를 파싱하는 differential 대조 구현 (테스트 전용).
 * 목적: 같은 JWT에서 no/nextUpdate/entries/AAGUID/status가 자체 NativeMetadataBlobVerifier와 일치하는지 대조.
 *
 * <p>이 클래스는 TEST source set 전용 — webauthn4j-metadata는 {@code testImplementation}
 * 의존이며 프로덕션 classpath에 절대 들어가지 않는다.
 *
 * <p>webauthn4j 0.31.5 API 확인 결과(소스 jar 검증):
 * <ul>
 *   <li>{@code new MetadataBLOBFactory(ObjectConverter)} → {@code MetadataBLOB parse(String)}.</li>
 *   <li>{@code MetadataBLOBFactory.parse(String)}는 <b>서명을 검증하지 않는다</b> — 내부적으로
 *       {@code JWSFactory.parse(value, MetadataBLOBPayload.class)}로 헤더/페이로드만 역직렬화한다.
 *       서명 검증은 별도의 {@code MetadataBLOB.isValidSignature()} 호출이다. 따라서 MdsTestBlob의
 *       self-signed JWS도 그대로 파싱된다(차등 테스트가 실 FIDO 루트를 요구하지 않음).</li>
 *   <li>{@code MetadataBLOBPayload}: {@code getNo()}(Integer), {@code getNextUpdate()}(LocalDate),
 *       {@code getEntries()}(List).</li>
 *   <li>entry: {@code getAaguid()}(AAGUID, nullable) → {@code AAGUID.getBytes()}(빅엔디안 16바이트,
 *       자체 {@code MdsBlobParser.uuidToBytes}와 동일 인코딩).</li>
 *   <li>statusReport: {@code getStatus()}(AuthenticatorStatus) → {@code getValue()}(원문 토큰 String),
 *       {@code getEffectiveDate()}(LocalDate, nullable).</li>
 * </ul>
 */
public final class Webauthn4jMetadataBlobVerifier {

    private final MetadataBLOBFactory factory = new MetadataBLOBFactory(new ObjectConverter());

    /** webauthn4j 파싱 결과를 자체 MdsBlob로 변환(필드 대조용). */
    public MdsBlob parse(String rawJwt) {
        MetadataBLOB blob = factory.parse(rawJwt);
        MetadataBLOBPayload payload = blob.getPayload();

        List<MdsBlobEntry> entries = new ArrayList<>();
        for (MetadataBLOBPayloadEntry e : payload.getEntries()) {
            AAGUID aaguid = e.getAaguid();
            byte[] aaguidBytes = (aaguid == null) ? null : aaguid.getBytes();

            List<MdsStatusReport> reports = new ArrayList<>();
            for (StatusReport sr : e.getStatusReports()) {
                String status = (sr.getStatus() == null) ? null : sr.getStatus().getValue();
                reports.add(new MdsStatusReport(status, sr.getEffectiveDate()));
            }
            entries.add(new MdsBlobEntry(aaguidBytes, reports));
        }
        return new MdsBlob(payload.getNo(), payload.getNextUpdate(), entries);
    }
}
