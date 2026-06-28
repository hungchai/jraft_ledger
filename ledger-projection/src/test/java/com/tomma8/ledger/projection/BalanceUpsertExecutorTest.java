package com.tomma8.ledger.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tomma8.ledger.dao.mapper.AccountBalanceMapper;

class BalanceUpsertExecutorTest {

    private static BalanceUpdate bu(String accountId, String currency, long seq) {
        return new BalanceUpdate(0, accountId, "AVAILABLE_BALANCE", currency,
                java.math.BigDecimal.ZERO, "CURRENT", seq, "JNL-" + seq);
    }

    @Test
    void emptySubmitCompletesImmediately() throws Exception {
        SqlSessionFactory sf = Mockito.mock(SqlSessionFactory.class);
        try (BalanceUpsertExecutor exec = new BalanceUpsertExecutor(sf, new SimpleMeterRegistry(), 16, 1000)) {
            CompletableFuture<Void> f = exec.submit(List.of());
            assertTrue(f.isDone(), "empty submit must complete immediately");
            assertNull(f.get());
        }
    }

    @Test
    void nullSubmitCompletesImmediately() throws Exception {
        SqlSessionFactory sf = Mockito.mock(SqlSessionFactory.class);
        try (BalanceUpsertExecutor exec = new BalanceUpsertExecutor(sf, new SimpleMeterRegistry(), 16, 1000)) {
            CompletableFuture<Void> f = exec.submit(null);
            assertTrue(f.isDone());
            assertNull(f.get());
        }
    }

    @Test
    void submitsInArrivalOrderAndAllFuturesComplete() throws Exception {
        SqlSessionFactory sf = mockSessionFactory();
        ExecutorService pump = Executors.newSingleThreadExecutor();
        try (BalanceUpsertExecutor exec = new BalanceUpsertExecutor(sf, new SimpleMeterRegistry(), 64, 1000)) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (long i = 1; i <= 5; i++) {
                final long seq = i;
                final BalanceUpdate row = new BalanceUpdate(0, "A", "AVAILABLE_BALANCE", "USD",
                        java.math.BigDecimal.valueOf(seq), "CURRENT", seq, "JNL-" + seq);
                Future<CompletableFuture<Void>> submitter = pump.submit(() -> exec.submit(List.of(row)));
                futures.add(submitter.get(1, TimeUnit.SECONDS));
            }
            for (CompletableFuture<Void> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
            Mockito.verify(sf, Mockito.times(5)).openSession(ExecutorType.BATCH);
        } finally {
            pump.shutdownNow();
        }
    }

    @Test
    void sqlFailureCompletesFutureExceptionally() throws Exception {
        SqlSessionFactory sf = Mockito.mock(SqlSessionFactory.class);
        SqlSession session = Mockito.mock(SqlSession.class);
        AccountBalanceMapper bm = Mockito.mock(AccountBalanceMapper.class);
        Mockito.when(sf.openSession(ExecutorType.BATCH)).thenReturn(session);
        Mockito.when(session.getMapper(AccountBalanceMapper.class)).thenReturn(bm);
        Mockito.doThrow(new RuntimeException("boom")).when(session).commit();

        try (BalanceUpsertExecutor exec = new BalanceUpsertExecutor(sf, new SimpleMeterRegistry(), 16, 1000)) {
            CompletableFuture<Void> f = exec.submit(List.of(bu("A", "USD", 1L)));
            ExecutionException ex = assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
            assertNotNull(ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("boom"));
        }
    }

    @Test
    void backpressureThrowsWhenQueueFull() throws Exception {
        // capacity=1, slow worker → second+ submit sits in queue; third waits submitTimeoutMs
        SqlSessionFactory sf = Mockito.mock(SqlSessionFactory.class);
        SqlSession session = Mockito.mock(SqlSession.class);
        AccountBalanceMapper bm = Mockito.mock(AccountBalanceMapper.class);
        Mockito.when(sf.openSession(ExecutorType.BATCH)).thenReturn(session);
        Mockito.when(session.getMapper(AccountBalanceMapper.class)).thenReturn(bm);
        Mockito.doAnswer(inv -> {
            Thread.sleep(500);
            return null;
        }).when(session).commit();

        try (BalanceUpsertExecutor exec = new BalanceUpsertExecutor(sf, new SimpleMeterRegistry(), 1, 100)) {
            CompletableFuture<Void> first = exec.submit(List.of(bu("A", "USD", 1L)));
            // Give worker time to pick up first task
            Thread.sleep(30);
            // Now the queue is empty (worker holds the task). The 2nd enqueues into the
            // capacity-1 queue. The 3rd has nowhere to go within 100ms.
            CompletableFuture<Void> second = exec.submit(List.of(bu("B", "USD", 2L)));
            Thread.sleep(20);
            CompletableFuture<Void> third = exec.submit(List.of(bu("C", "USD", 3L)));
            ExecutionException ex = assertThrows(ExecutionException.class, () -> third.get(2, TimeUnit.SECONDS));
            assertNotNull(ex.getCause());
            assertTrue(ex.getCause() instanceof BalanceUpsertExecutor.BalanceUpsertBackpressureException);
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeCompletesPendingFuturesNormally() throws Exception {
        SqlSessionFactory sf = mockSessionFactory();

        BalanceUpsertExecutor exec = new BalanceUpsertExecutor(sf, new SimpleMeterRegistry(), 8, 1000);
        CompletableFuture<Void> f = exec.submit(List.of(bu("A", "USD", 1L)));
        exec.close();
        f.get(5, TimeUnit.SECONDS);
        assertFalse(f.isCompletedExceptionally());
    }

    private static SqlSessionFactory mockSessionFactory() {
        SqlSessionFactory sf = Mockito.mock(SqlSessionFactory.class);
        SqlSession session = Mockito.mock(SqlSession.class);
        AccountBalanceMapper bm = Mockito.mock(AccountBalanceMapper.class);
        Mockito.when(sf.openSession(ExecutorType.BATCH)).thenReturn(session);
        Mockito.when(session.getMapper(AccountBalanceMapper.class)).thenReturn(bm);
        return sf;
    }
}