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

# (?<!PSK_) : 이미 접두사가 붙은 토큰 제외
# (?<![A-Z0-9_]) / (?![A-Z0-9_]) : 단어 경계. \b 는 밑줄을 단어문자로 봐서
#   APP_ADMIN_USER 안의 APP_ADMIN 을 걸러내지 못하므로 직접 명시한다.
PATTERNS = [
    (re.compile(r"(?<!PSK_)(?<![A-Z0-9_])" + old + r"(?![A-Z0-9_])"), new)
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
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(converted)
            changed += 1
    print(f"changed: {changed}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
