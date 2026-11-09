package com.crosscert.passkey.admin.operator

import com.crosscert.passkey.core.mail.MailSender
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.Executor

private val log = LoggerFactory.getLogger(AfterCommitMailDispatcher::class.java)

/**
 * 비밀번호 재설정 메일을 트랜잭션 커밋 이후 전용 스레드에서 발송한다 (S9).
 *
 * 요청 스레드가 SMTP 왕복을 기다리지 않으므로 "존재하는 계정만 메일 전송" 차이가
 * 응답 시간으로 드러나지 않는다. 메일 수신자·제목·본문은 호출자가 만든 값을 그대로 쓰고,
 * 커밋당 정확히 1회 발송한다(재시도 없음). 롤백되면 보내지 않는다.
 *
 * 발송 실패는 기존과 같이 조용히 삼킨다 — `SmtpMailSender` 가 이미
 * `[MAIL] send failed` warn 로그를 남긴다. 요청 MDC(traceId 등)를 작업에 복사해
 * 그 로그가 요청과 계속 연관된다.
 *
 * Executor 는 Spring 빈으로 등록하지 않는다(기존 `alertExecutor`·`@Async`
 * 기본 executor 해석에 영향 없음). 큐 포화 또는 종료 이후에 거부되면 호출 스레드에서
 * 즉시 동기 발송해 메일을 버리지 않는다. (`CallerRunsPolicy` 는 종료된 풀에서
 * 작업을 조용히 버리므로 쓰지 않는다.)
 */
@Component
class AfterCommitMailDispatcher internal constructor(
    private val mail: MailSender,
    private val executor: Executor,
) : DisposableBean {

    private val ownedExecutor: ThreadPoolTaskExecutor? = executor as? ThreadPoolTaskExecutor

    @Autowired
    constructor(mail: MailSender) : this(mail, newOwnedExecutor())

    /**
     * 활성 트랜잭션이 있으면 커밋 직후, 없으면 즉시 전용 executor 에 발송 작업을 넣는다.
     * 정상 상태에서 호출 스레드는 SMTP 를 기다리지 않는다.
     */
    fun sendAfterCommit(to: String, subject: String, body: String) {
        val task = withCallerMdc { sendQuietly(to, subject, body) }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    dispatch(task)
                }
            })
        } else {
            dispatch(task)
        }
    }

    private fun dispatch(task: Runnable) {
        try {
            executor.execute(task)
        } catch (e: RuntimeException) {
            // 방어용 — afterCommit 예외가 요청 응답(200)으로 번지지 않게 한다.
            log.warn("password reset mail dispatch failed: {}", e.toString())
        }
    }

    private fun sendQuietly(to: String, subject: String, body: String) {
        try {
            mail.send(to, subject, body)
        } catch (ignore: Exception) {
            // 메일 실패해도 토큰은 발급됨 (실패 로그는 SmtpMailSender 가 남김)
        }
    }

    override fun destroy() {
        ownedExecutor?.shutdown()
    }

    companion object {
        internal const val THREAD_NAME_PREFIX = "pwreset-mail-"

        private fun newOwnedExecutor(): ThreadPoolTaskExecutor {
            val ex = ThreadPoolTaskExecutor()
            ex.corePoolSize = 2
            ex.maxPoolSize = 2
            ex.queueCapacity = 100
            ex.setThreadNamePrefix(THREAD_NAME_PREFIX)
            // 포화·종료 어느 쪽이든 거부된 작업은 호출 스레드에서 1회 실행 — 메일 유실 없음.
            ex.setRejectedExecutionHandler { r, _ -> r.run() }
            ex.setWaitForTasksToCompleteOnShutdown(true)
            ex.setAwaitTerminationSeconds(10)
            ex.initialize()
            return ex
        }

        /** 호출 시점 MDC 를 작업에 복사하고, 실행 후 실행 스레드의 원래 MDC 로 되돌린다(인라인 실행 대비). */
        private fun withCallerMdc(task: Runnable): Runnable {
            val captured = MDC.getCopyOfContextMap()
            return Runnable {
                val previous = MDC.getCopyOfContextMap()
                if (captured == null) MDC.clear() else MDC.setContextMap(captured)
                try {
                    task.run()
                } finally {
                    if (previous == null) MDC.clear() else MDC.setContextMap(previous)
                }
            }
        }
    }
}
