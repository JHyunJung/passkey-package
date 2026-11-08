package com.crosscert.passkey.admin.web.format;

import com.crosscert.passkey.admin.policy.SecurityPolicyDto;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 보안 정책(설정 › 보안 정책) 입력 검증(← admin-ui lib/policyValidation.ts). 저장 전에 형식 오류를 그 자리에서 알린다. */
public final class PolicyValidation {

    public static final String ORIGIN_FORMAT_ERROR = "형식이 올바르지 않습니다. https:// 로 시작하고 경로 없이 입력하세요. 예: https://admin.example.com";
    public static final String ORIGIN_DUPLICATE_ERROR = "이미 목록에 있습니다.";
    public static final String ORIGIN_DEFAULT_PORT_ERROR = "기본 포트(https :443, http :80)는 적지 않습니다. 포트를 빼고 입력하세요.";
    public static final String ORIGIN_LENGTH_ERROR = "origin 은 256자 이하로 입력하세요.";
    public static final String ORIGIN_COUNT_ERROR = "origin 은 최대 64개까지 등록할 수 있습니다.";
    public static final String TIMEOUT_ERROR = "1–1440 사이의 분을 입력하세요. 비워 둘 수 없습니다.";

    public static final int MAX_ORIGIN_LENGTH = 256;
    public static final int MAX_ORIGINS = 64;

    private static final Pattern ORIGIN_RE = Pattern.compile(
            "(https?)://([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*)(?::(\\d{1,5}))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1");
    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger MAX_TIMEOUT = BigInteger.valueOf(1440);

    private PolicyValidation() {}

    /** 저장용 정규형. 앞뒤 공백을 지우고 소문자로 맞춘다(origin 에는 경로가 없어 전체를 소문자로 바꿔도 같다). */
    public static String normalizeOrigin(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    /** CORS origin 검증. https://호스트[:포트] 만(경로·와일드카드·IPv6·유니코드 호스트 불가). http 는 로컬 개발 주소만. 통과하면 null. */
    public static String validateOrigin(String value, List<String> existing) {
        String v = value.strip();
        if (v.length() > MAX_ORIGIN_LENGTH) return ORIGIN_LENGTH_ERROR;
        Matcher m = ORIGIN_RE.matcher(v);
        if (!m.matches()) return ORIGIN_FORMAT_ERROR;
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        String host = m.group(2).toLowerCase(Locale.ROOT);
        String port = m.group(3);
        if (scheme.equals("http") && !LOCAL_HOSTS.contains(host)) return ORIGIN_FORMAT_ERROR;
        if (port != null) {
            if (port.length() > 1 && port.startsWith("0")) return ORIGIN_FORMAT_ERROR;
            int n = Integer.parseInt(port);
            if (n < 1 || n > 65535) return ORIGIN_FORMAT_ERROR;
            if ((scheme.equals("https") && n == 443) || (scheme.equals("http") && n == 80)) return ORIGIN_DEFAULT_PORT_ERROR;
        }
        String key = normalizeOrigin(v);
        if (existing.stream().anyMatch(o -> normalizeOrigin(o).equals(key))) return ORIGIN_DUPLICATE_ERROR;
        if (existing.size() >= MAX_ORIGINS) return ORIGIN_COUNT_ERROR;
        return null;
    }

    /** 세션 idle timeout(분) 검증. 정수 1–1440(최대 24시간)만 통과. */
    public static String timeoutError(String raw) {
        String v = raw.strip();
        if (!DIGITS.matcher(v).matches()) return TIMEOUT_ERROR;
        BigInteger n = new BigInteger(v);
        return n.compareTo(ONE) < 0 || n.compareTo(MAX_TIMEOUT) > 0 ? TIMEOUT_ERROR : null;
    }

    /**
     * 보안 정책 저장 요청 검증. 키는 필드명(sessionIdleTimeoutMinutes, corsAllowlist), 값은 오류 문구.
     * corsAllowlist 는 앞에서부터 하나씩 이미 통과한 항목들과 비교해 첫 오류만 담는다. 오류가 없으면 빈 맵.
     */
    public static Map<String, String> validate(SecurityPolicyDto.UpdateRequest req) {
        Map<String, String> errors = new LinkedHashMap<>();
        String timeout = timeoutError(String.valueOf(req.getSessionIdleTimeoutMinutes()));
        if (timeout != null) errors.put("sessionIdleTimeoutMinutes", timeout);
        List<String> list = req.getCorsAllowlist();
        if (list != null) {
            if (list.size() > MAX_ORIGINS) {
                errors.put("corsAllowlist", ORIGIN_COUNT_ERROR);
            } else {
                List<String> accepted = new ArrayList<>();
                for (String origin : list) {
                    String err = origin == null ? ORIGIN_FORMAT_ERROR : validateOrigin(origin, accepted);
                    if (err != null) {
                        errors.put("corsAllowlist", err);
                        break;
                    }
                    accepted.add(origin);
                }
            }
        }
        return errors;
    }
}
