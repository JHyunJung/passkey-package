package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUserRecoveryCode;
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository;
import com.crosscert.passkey.core.util.CryptoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Admin MFA 1회용 recovery code 발급/소비 (P0 잔여 A).
 *
 * <p>발급(generate): 기존 코드 전량 폐기 후 N 개 평문 생성 → sha-256 hash 만 저장 →
 * 평문 List 를 1회 반환(이후 평문 복구 불가). 소비(consume): 미사용 매칭 코드 1개를
 * used_at 마킹(one-shot). 평문은 Base32 4-4 형식("xxxx-xxxx")으로 입력 편의 제공.
 */
@Service
@RequiredArgsConstructor
public class RecoveryCodeService {

    static final int CODE_COUNT = 10;
    private static final int GROUP_LEN = 4; // "xxxx-xxxx"
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 혼동 문자 제외
    private static final SecureRandom RNG = new SecureRandom();

    private final AdminUserRecoveryCodeRepository repo;
    private final Clock clock;

    @Transactional
    public List<String> generate(UUID adminUserId) {
        repo.deleteByAdminUserId(adminUserId);
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        while (codes.size() < CODE_COUNT) {
            codes.add(randomCode());
        }
        for (String code : codes) {
            repo.save(new AdminUserRecoveryCode(adminUserId, CryptoUtils.sha256Hex(code)));
        }
        return new ArrayList<>(codes);
    }

    @Transactional
    public boolean consume(UUID adminUserId, String code) {
        if (code == null || code.isBlank()) return false;
        String hash = CryptoUtils.sha256Hex(normalize(code));
        return repo.markUsed(adminUserId, hash, clock.instant()) == 1;
    }

    @Transactional(readOnly = true)
    public long remaining(UUID adminUserId) {
        return repo.countByAdminUserIdAndUsedAtIsNull(adminUserId);
    }

    private static String normalize(String code) {
        return code.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(GROUP_LEN * 2 + 1);
        for (int i = 0; i < GROUP_LEN * 2; i++) {
            if (i == GROUP_LEN) sb.append('-');
            sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
