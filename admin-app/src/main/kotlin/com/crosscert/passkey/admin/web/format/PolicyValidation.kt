package com.crosscert.passkey.admin.web.format

import com.crosscert.passkey.admin.policy.SecurityPolicyDto
import java.math.BigInteger
import java.util.regex.Pattern

private val ORIGIN_RE: Pattern = Pattern.compile(
    "(https?)://([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*)(?::(\\d{1,5}))?",
    Pattern.CASE_INSENSITIVE,
)
private val DIGITS: Pattern = Pattern.compile("\\d+")
private val LOCAL_HOSTS: Set<String> = java.util.Set.of("localhost", "127.0.0.1")
private val ONE: BigInteger = BigInteger.ONE
private val MAX_TIMEOUT: BigInteger = BigInteger.valueOf(1440)

/** 보안 정책(설정 › 보안 정책) 입력 검증(← admin-ui lib/policyValidation.ts). 저장 전에 형식 오류를 그 자리에서 알린다. */
object PolicyValidation {

    const val ORIGIN_FORMAT_ERROR = "형식이 올바르지 않습니다. https:// 로 시작하고 경로 없이 입력하세요. 예: https://admin.example.com"
    const val ORIGIN_DUPLICATE_ERROR = "이미 목록에 있습니다."
    const val ORIGIN_DEFAULT_PORT_ERROR = "기본 포트(https :443, http :80)는 적지 않습니다. 포트를 빼고 입력하세요."
    const val ORIGIN_LENGTH_ERROR = "origin 은 256자 이하로 입력하세요."
    const val ORIGIN_COUNT_ERROR = "origin 은 최대 64개까지 등록할 수 있습니다."
    const val TIMEOUT_ERROR = "1–1440 사이의 분을 입력하세요. 비워 둘 수 없습니다."

    const val MAX_ORIGIN_LENGTH = 256
    const val MAX_ORIGINS = 64

    /** 저장용 정규형. 앞뒤 공백을 지우고 소문자로 맞춘다(origin 에는 경로가 없어 전체를 소문자로 바꿔도 같다). */
    fun normalizeOrigin(value: String): String = value.javaStrip().lowercase()

    /** CORS origin 검증. https://호스트[:포트] 만(경로·와일드카드·IPv6·유니코드 호스트 불가). http 는 로컬 개발 주소만. 통과하면 null. */
    fun validateOrigin(value: String, existing: List<String>): String? {
        val v = value.javaStrip()
        if (v.length > MAX_ORIGIN_LENGTH) return ORIGIN_LENGTH_ERROR
        val m = ORIGIN_RE.matcher(v)
        if (!m.matches()) return ORIGIN_FORMAT_ERROR
        val scheme = m.group(1).lowercase()
        val host = m.group(2).lowercase()
        val port: String? = m.group(3)
        if (scheme == "http" && !LOCAL_HOSTS.contains(host)) return ORIGIN_FORMAT_ERROR
        if (port != null) {
            if (port.length > 1 && port.startsWith("0")) return ORIGIN_FORMAT_ERROR
            val n = Integer.parseInt(port)
            if (n < 1 || n > 65535) return ORIGIN_FORMAT_ERROR
            if ((scheme == "https" && n == 443) || (scheme == "http" && n == 80)) return ORIGIN_DEFAULT_PORT_ERROR
        }
        val key = normalizeOrigin(v)
        if (existing.stream().anyMatch { o -> normalizeOrigin(o) == key }) return ORIGIN_DUPLICATE_ERROR
        if (existing.size >= MAX_ORIGINS) return ORIGIN_COUNT_ERROR
        return null
    }

    /** 세션 idle timeout(분) 검증. 정수 1–1440(최대 24시간)만 통과. */
    fun timeoutError(raw: String): String? {
        val v = raw.javaStrip()
        if (!DIGITS.matcher(v).matches()) return TIMEOUT_ERROR
        val n = BigInteger(v)
        return if (n < ONE || n > MAX_TIMEOUT) TIMEOUT_ERROR else null
    }

    /**
     * 보안 정책 저장 요청 검증. 키는 필드명(sessionIdleTimeoutMinutes, corsAllowlist), 값은 오류 문구.
     * corsAllowlist 는 앞에서부터 하나씩 이미 통과한 항목들과 비교해 첫 오류만 담는다. 오류가 없으면 빈 맵.
     */
    fun validate(req: SecurityPolicyDto.UpdateRequest): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        val timeout = timeoutError(req.sessionIdleTimeoutMinutes.toString())
        if (timeout != null) errors["sessionIdleTimeoutMinutes"] = timeout
        val list = req.corsAllowlist
        if (list != null) {
            if (list.size > MAX_ORIGINS) {
                errors["corsAllowlist"] = ORIGIN_COUNT_ERROR
            } else {
                val accepted = ArrayList<String>()
                for (element in list) {
                    // 원소 타입은 non-null 이지만 원 Java 의 null 원소 판정을 그대로 둔다.
                    val origin: String? = element
                    val err = if (origin == null) ORIGIN_FORMAT_ERROR else validateOrigin(origin, accepted)
                    if (err != null) {
                        errors["corsAllowlist"] = err
                        break
                    }
                    accepted.add(element)
                }
            }
        }
        return errors
    }
}
