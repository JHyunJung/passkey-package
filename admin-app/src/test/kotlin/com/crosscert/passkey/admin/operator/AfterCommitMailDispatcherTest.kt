package com.crosscert.passkey.admin.operator

import com.crosscert.passkey.core.mail.MailSender
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.function.Executable
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.MDC
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * S9 — [AfterCommitMailDispatcher]: 커밋 이후에만, 정확히 1회, 호출 스레드를 막지 않고
 * 발송하는지와 실패·MDC·종료 처리가 기존 의미(메일 유실 없음)를 유지하는지 검증한다.
 */
@ExtendWith(MockitoExtension::class)
class AfterCommitMailDispatcherTest {

    @Mock
    lateinit var mail: MailSender

    @AfterEach
    fun cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
        MDC.clear()
    }

    @Test
    fun with_active_tx_task_is_submitted_only_after_commit_and_once() {
        val submitted = mutableListOf<Runnable>()
        val d = AfterCommitMailDispatcher(mail, Executor { submitted.add(it) })
        TransactionSynchronizationManager.initSynchronization()
        try {
            d.sendAfterCommit("op@x.com", "subj", "body")
            assertThat(submitted).isEmpty()
            verify(mail, never()).send(anyOrNull(), anyOrNull(), anyOrNull())

            val syncs = TransactionSynchronizationManager.getSynchronizations()
            assertThat(syncs).hasSize(1)
            syncs[0].afterCommit()
            assertThat(submitted).hasSize(1)

            submitted[0].run()
            verify(mail, times(1)).send("op@x.com", "subj", "body")
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun rollback_never_submits() {
        val submitted = mutableListOf<Runnable>()
        val d = AfterCommitMailDispatcher(mail, Executor { submitted.add(it) })
        TransactionSynchronizationManager.initSynchronization()
        try {
            d.sendAfterCommit("op@x.com", "subj", "body")
            TransactionSynchronizationManager.getSynchronizations()
                .forEach { it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK) }
            assertThat(submitted).isEmpty()
            verify(mail, never()).send(anyOrNull(), anyOrNull(), anyOrNull())
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    /**
     * Review Focus 4 — 수동 동기화가 아닌 실제 [AbstractPlatformTransactionManager] 의
     * 커밋·롤백 처리에서 커밋은 정확히 1회 제출, 롤백은 0회 제출임을 확인한다.
     */
    @Test
    fun real_transaction_manager_commit_submits_once_rollback_submits_none() {
        val submitted = mutableListOf<Runnable>()
        val d = AfterCommitMailDispatcher(mail, Executor { submitted.add(it) })
        val tm: PlatformTransactionManager = object : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()
            override fun doBegin(transaction: Any, definition: TransactionDefinition) {}
            override fun doCommit(status: DefaultTransactionStatus) {}
            override fun doRollback(status: DefaultTransactionStatus) {}
        }
        val tt = TransactionTemplate(tm)

        tt.executeWithoutResult { d.sendAfterCommit("op@x.com", "subj", "body") }
        assertThat(submitted).hasSize(1)

        tt.executeWithoutResult { st ->
            d.sendAfterCommit("op@x.com", "subj", "body")
            st.setRollbackOnly()
        }
        assertThat(submitted).hasSize(1)

        submitted[0].run()
        verify(mail, times(1)).send("op@x.com", "subj", "body")
    }

    @Test
    fun without_tx_task_is_submitted_immediately() {
        val submitted = mutableListOf<Runnable>()
        val d = AfterCommitMailDispatcher(mail, Executor { submitted.add(it) })
        d.sendAfterCommit("op@x.com", "subj", "body")
        assertThat(submitted).hasSize(1)
        submitted[0].run()
        verify(mail, times(1)).send("op@x.com", "subj", "body")
    }

    @Test
    fun mail_failure_is_swallowed() {
        doThrow(RuntimeException("mail send failed")).whenever(mail).send(anyString(), anyString(), anyString())
        val d = AfterCommitMailDispatcher(mail, Executor { it.run() })
        assertThatCode { d.sendAfterCommit("op@x.com", "subj", "body") }.doesNotThrowAnyException()
        verify(mail, times(1)).send("op@x.com", "subj", "body")
    }

    @Test
    fun executor_exception_does_not_propagate_from_after_commit() {
        val d = AfterCommitMailDispatcher(mail, Executor { throw RejectedExecutionException("rejected") })
        TransactionSynchronizationManager.initSynchronization()
        try {
            d.sendAfterCommit("op@x.com", "subj", "body")
            assertThatCode {
                TransactionSynchronizationManager.getSynchronizations()
                    .forEach { it.afterCommit() }
            }.doesNotThrowAnyException()
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun mdc_is_propagated_to_task_and_restored_on_caller() {
        val seen = AtomicReference<String?>()
        doAnswer {
            seen.set(MDC.get("traceId"))
            MDC.put("traceId", "mutated")
            null
        }.whenever(mail).send(anyString(), anyString(), anyString())
        val d = AfterCommitMailDispatcher(mail, Executor { it.run() })
        MDC.put("traceId", "t-1")

        d.sendAfterCommit("op@x.com", "subj", "body")

        assertThat(seen.get()).isEqualTo("t-1")
        assertThat(MDC.get("traceId")).isEqualTo("t-1")
    }

    @Test
    fun default_executor_sends_once_on_dedicated_thread_without_blocking_caller() {
        val release = CountDownLatch(1)
        val sent = CountDownLatch(1)
        val threadName = AtomicReference<String?>()
        doAnswer {
            release.await(5, TimeUnit.SECONDS)
            threadName.set(Thread.currentThread().name)
            sent.countDown()
            null
        }.whenever(mail).send(anyString(), anyString(), anyString())

        val d = AfterCommitMailDispatcher(mail)
        try {
            // SMTP 가 막혀 있어도 호출자는 즉시 돌아온다(응답 시간 독립).
            assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                Executable { d.sendAfterCommit("op@x.com", "subj", "body") },
            )
            release.countDown()
            assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(threadName.get()).startsWith(AfterCommitMailDispatcher.THREAD_NAME_PREFIX)
            verify(mail, times(1)).send("op@x.com", "subj", "body")
        } finally {
            d.destroy()
        }
    }

    /** 실제 내부 풀이 종료된 뒤에도 메일을 조용히 버리지 않고 호출 스레드에서 1회 보낸다. */
    @Test
    fun after_destroy_mail_is_still_sent_once_inline() {
        val threadName = AtomicReference<String?>()
        doAnswer {
            threadName.set(Thread.currentThread().name)
            null
        }.whenever(mail).send(anyString(), anyString(), anyString())
        val d = AfterCommitMailDispatcher(mail)
        d.destroy()

        assertThatCode { d.sendAfterCommit("op@x.com", "subj", "body") }.doesNotThrowAnyException()

        verify(mail, times(1)).send("op@x.com", "subj", "body")
        assertThat(threadName.get()).isEqualTo(Thread.currentThread().name)
    }
}
