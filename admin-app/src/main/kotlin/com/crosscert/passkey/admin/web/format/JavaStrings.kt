package com.crosscert.passkey.admin.web.format

/*
 * Java String 판정을 그대로 옮긴 확장 함수. Kotlin 기본판(trim()/isBlank())은 Char.isWhitespace 로
 * NBSP 등 유니코드 공백까지 보아 결과가 다를 수 있다. 보조 평면에는 공백 문자가 없어 char 단위 판정이 code point 판정과 같다.
 */

/** Java `String.strip()` — 앞뒤의 Character.isWhitespace 문자를 지운다. */
internal fun String.javaStrip(): String = trim(Character::isWhitespace)

/** Java `String.isBlank()` — 비었거나 Character.isWhitespace 문자로만 되어 있다. */
internal fun String.javaIsBlank(): Boolean = all(Character::isWhitespace)

/** Java `String.trim()` — 앞뒤의 U+0020 이하 문자를 지운다. */
internal fun String.javaTrim(): String = trim { it <= ' ' }
