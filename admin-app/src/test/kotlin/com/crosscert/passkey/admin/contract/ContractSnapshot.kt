package com.crosscert.passkey.admin.contract

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 계약 스냅샷 기록·비교. 파일은 `src/test/resources/contract/` 아래에 둔다.
 *
 * 기본(비교) 모드: 파일이 없거나 내용이 다르면 실패한다. 기록 모드
 * (`./gradlew :admin-app:test --tests '<cls>' -Pcontract.record=true`)에서는 **없는** 파일만 새로 쓰고
 * 있는 파일은 그대로 비교한다(동결 보호 — 덮어쓰기는 [OVERWRITE] 를 명시).
 * 기록한 파일은 커밋 전에 사람이 검토하고, 그 뒤로는 수정하지 않는다(새 스냅샷 추가만 허용).
 */
internal object ContractSnapshot {

    val RECORD: Boolean = java.lang.Boolean.getBoolean("contract.record")

    /**
     * 기록 모드에서 **이미 있는** 파일까지 덮어쓸지. 기본은 끔 — 기록 모드는 없는 파일만 새로 쓰고, 있는 파일(동결)은
     * 비교한다. 의도적 갱신만 `CONTRACT_OVERWRITE=true ./gradlew ... -Pcontract.record=true`(환경변수 — 빌드 파일이
     * 테스트 JVM 에 넘기는 시스템 속성은 contract.record 뿐이라) 또는 시스템 속성 `contract.overwrite=true` 로 켠다.
     */
    val OVERWRITE: Boolean = java.lang.Boolean.getBoolean("contract.overwrite") ||
        "true".equals(System.getenv("CONTRACT_OVERWRITE"), ignoreCase = true)

    private val ROOT: Path = Path.of("src/test/resources/contract")

    /** 기록 모드에서 이 파일을 쓸지 — 없는 파일이거나 덮어쓰기를 명시했을 때만. */
    private fun shouldWrite(file: Path): Boolean = RECORD && (OVERWRITE || !Files.exists(file))

    /** 텍스트 스냅샷(UTF-8). */
    fun assertMatches(relativePath: String, actual: String) {
        val file = ROOT.resolve(relativePath)
        try {
            if (shouldWrite(file)) {
                Files.createDirectories(file.parent)
                Files.writeString(file, actual)
                return
            }
            if (!Files.exists(file)) {
                fail<Any>("스냅샷 없음: $file — -Pcontract.record=true 로 기록 후 검토하세요")
            }
            assertThat(actual).`as`("contract/$relativePath").isEqualTo(Files.readString(file))
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    /** 바이트 스냅샷 — 첫 차이 위치를 텍스트로 보이도록 UTF-8 로 디코드해 비교하고, 바이트 길이·내용도 단언한다. */
    fun assertBytesMatch(relativePath: String, actual: ByteArray) {
        val file = ROOT.resolve(relativePath)
        try {
            if (shouldWrite(file)) {
                Files.createDirectories(file.parent)
                Files.write(file, actual)
                return
            }
            if (!Files.exists(file)) {
                fail<Any>("스냅샷 없음: $file — -Pcontract.record=true 로 기록 후 검토하세요")
            }
            val expected = Files.readAllBytes(file)
            assertThat(String(actual, StandardCharsets.UTF_8))
                .`as`("contract/$relativePath (UTF-8 view)")
                .isEqualTo(String(expected, StandardCharsets.UTF_8))
            assertThat(actual).`as`("contract/$relativePath (bytes)").isEqualTo(expected)
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }
}
