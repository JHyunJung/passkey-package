#!/usr/bin/env python3
"""DB 계정·롤에 PSK_ 접두사를 붙이는 일괄 치환 도구.

핵심 제약 두 가지를 코드로 보장한다.
  1) 긴 이름 우선 — APP_ADMIN 은 APP_ADMIN_USER 의 부분문자열이라
     짧은 것부터 치환하면 PSK_APP_ADMIN_USER 가 아니라 깨진 이름이 된다.
  2) 이중 접두사 금지 — 이미 PSK_ 가 붙은 토큰은 건너뛴다.
"""
import re
import sys

# 순서가 곧 우선순위다. 긴 이름부터.
MAPPING = [
    ("APP_RUNTIME_USER", "PSK_APP_RUNTIME_USER"),
    ("APP_ADMIN_USER",   "PSK_APP_ADMIN_USER"),
    ("APP_RUNTIME",      "PSK_APP_RUNTIME"),
    ("APP_ADMIN",        "PSK_APP_ADMIN"),
    ("APP_OWNER",        "PSK_APP_OWNER"),
]

# 경계 문자: 식별자의 일부가 될 수 있는 것 전부.
#   - ASCII 영숫자·밑줄
#   - Oracle 식별자에 허용되는 $ 와 #
#   - 유니코드 문자(주석·문서에 한글 등이 바로 이어지는 경우)
# 이것들이 앞뒤에 붙어 있으면 "다른 토큰의 일부"이므로 치환하지 않는다.
# \w 는 Python3 에서 기본이 유니코드이므로 한글도 단어문자로 잡힌다.
PATTERNS = [
    (re.compile(
        r"(?<!PSK_)"           # 이미 접두사가 붙은 토큰 제외
        r"(?<![\w\$#])"        # 앞 경계: 단어문자(유니코드 포함)·$·# 가 아니어야
        + old +
        r"(?![\w\$#])"         # 뒤 경계: 같음
    ), new)
    for old, new in MAPPING
]


def convert(text: str) -> str:
    for pattern, new in PATTERNS:
        text = pattern.sub(new, text)
    return text


def self_test() -> int:
    cases = [
        # (입력, 기대)
        ("GRANT SELECT ON t TO APP_ADMIN;", "GRANT SELECT ON t TO PSK_APP_ADMIN;"),
        ("GRANT APP_ADMIN TO APP_ADMIN_USER;", "GRANT PSK_APP_ADMIN TO PSK_APP_ADMIN_USER;"),
        ("username: APP_RUNTIME_USER", "username: PSK_APP_RUNTIME_USER"),
        ("FROM APP_OWNER.mds_sync_history", "FROM PSK_APP_OWNER.mds_sync_history"),
        # 이미 치환된 것은 그대로 (멱등성)
        ("TO PSK_APP_ADMIN_USER;", "TO PSK_APP_ADMIN_USER;"),
        ("PSK_APP_OWNER.tenant", "PSK_APP_OWNER.tenant"),
        # 부분문자열이 아닌 다른 식별자는 건드리지 않는다
        ("MY_APP_ADMINISTRATOR", "MY_APP_ADMINISTRATOR"),
        ("APP_ADMINX", "APP_ADMINX"),
        # 뒤에 소문자가 이어지면 다른 단어다 — 건드리지 않는다
        ("APP_ADMINistrator", "APP_ADMINistrator"),
        ("APP_OWNERship", "APP_OWNERship"),
        # Oracle 식별자에 허용되는 $ · # 가 이어지면 다른 토큰이다
        ("APP_ADMIN$AUDIT", "APP_ADMIN$AUDIT"),
        ("APP_OWNER#1", "APP_OWNER#1"),
        # 유니코드 문자가 바로 이어지는 경우
        ("APP_OWNER한글", "APP_OWNER한글"),
        # 앞에 $ 가 붙은 경우도 다른 토큰
        ("X$APP_ADMIN", "X$APP_ADMIN"),
    ]
    failed = 0
    for src, expected in cases:
        got = convert(src)
        if got != expected:
            print(f"FAIL: {src!r}\n  expected {expected!r}\n  got      {got!r}")
            failed += 1
    # 멱등성: 두 번 돌려도 같아야 한다
    sample = "GRANT APP_ADMIN TO APP_ADMIN_USER; FROM APP_OWNER.t"
    once = convert(sample)
    twice = convert(once)
    if once != twice:
        print(f"FAIL(idempotent): {once!r} != {twice!r}")
        failed += 1
    print("self-test: FAILED" if failed else f"self-test: OK ({len(cases)} cases + idempotency)")
    return 1 if failed else 0


def main(argv):
    if "--self-test" in argv:
        return self_test()
    paths = [a for a in argv if not a.startswith("-")]
    if not paths:
        print("usage: rename-psk-prefix.py <file>...  |  --self-test", file=sys.stderr)
        return 2
    changed = 0
    for path in paths:
        try:
            with open(path, "r", encoding="utf-8") as fh:
                original = fh.read()
        except (UnicodeDecodeError, IsADirectoryError):
            continue  # 바이너리·디렉터리는 건너뛴다
        converted = convert(original)
        if converted != original:
            # 비원자적 쓰기지만 대상이 전부 git 추적 파일이라 손상 시 checkout 으로
            # 복구된다. 일회성 도구이므로 임시파일+os.replace 는 과설계(YAGNI).
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(converted)
            changed += 1
    print(f"changed: {changed}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
