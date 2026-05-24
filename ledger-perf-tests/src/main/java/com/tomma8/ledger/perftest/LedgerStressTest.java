package com.tomma8.ledger.perftest;

import com.tomma8.ledger.client.LedgerClient;
import com.tomma8.ledger.client.LedgerClientConfig;
import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.model.EntryType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

public class LedgerStressTest {

    public static void main(String[] args) throws Exception {
        String endpointsStr = System.getProperty("endpoints", "http://localhost:8081");
        String phase = System.getProperty("phase", "all");
        int concurrency = Integer.getInteger("concurrency", 100);
        int durationSec = Integer.getInteger("duration", 60);
        String hotspot = System.getProperty("hotspot", "STRESS-HOT-CO-001");
        int accountCount = Integer.getInteger("accounts", 1000);
        String prefix = System.getProperty("prefix", "STRESS-CLI-");

        List<String> endpoints = List.of(endpointsStr.split(","));
        LedgerClientConfig config = LedgerClientConfig.builder()
                .endpoints(endpoints)
                .maxRetries(3)
                .build();

        try (LedgerClient client = new LedgerClient(config)) {
            switch (phase) {
                case "rfq" -> runRfq(client, hotspot, prefix, accountCount, concurrency);
                case "race" -> runRace(client, hotspot, prefix, concurrency);
                case "readwrite" -> runReadWrite(client, hotspot, prefix, accountCount, concurrency, durationSec);
                case "all" -> {
                    runRfq(client, hotspot, prefix, accountCount, concurrency);
                    runRace(client, hotspot, prefix, concurrency);
                    runReadWrite(client, hotspot, prefix, accountCount, concurrency, durationSec);
                }
                default -> throw new IllegalArgumentException("Unknown phase: " + phase);
            }
        }
    }

    private static void runRfq(LedgerClient client, String hotspot, String prefix, int accountCount, int concurrency) throws Exception {
        System.out.println("--- RFQ Phase ---");
        LongAdder ok = new LongAdder();
        LongAdder fail = new LongAdder();
        int total = concurrency * 100;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        long start = System.currentTimeMillis();

        for (int i = 0; i < total; i++) {
            final int idx = i;
            pool.submit(() -> {
                String acc = prefix + String.format("%04d", (idx % accountCount) + 1);
                PostingCommand cmd = new PostingCommand(
                        UUID.randomUUID().toString(),
                        "RFQ",
                        "RFQ-" + idx,
                        LocalDate.of(2026, 5, 23),
                        List.of(new PostingCommand.Leg(
                                "leg-1",
                                "RFQ",
                                new BigDecimal("1.00"),
                                "USD",
                                List.of(
                                        new PostingCommand.Line(acc, "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "RFQ"),
                                        new PostingCommand.Line(hotspot, "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "RFQ")
                                )
                        ))
                );
                try {
                    CommandResult r = client.post(cmd);
                    if (r.isCompleted()) ok.increment();
                    else fail.increment();
                } catch (Exception e) {
                    fail.increment();
                }
            });
        }

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.MINUTES);
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("RFQ: total=%d, ok=%d, fail=%d, time=%dms, throughput=%.1f req/s%n",
                total, ok.sum(), fail.sum(), elapsed, total * 1000.0 / elapsed);
    }

    private static void runRace(LedgerClient client, String hotspot, String prefix, int concurrency) throws Exception {
        System.out.println("--- Race Phase ---");
        String sameAcc = prefix + "MAX-001";
        LongAdder ok = new LongAdder();
        LongAdder rej = new LongAdder();
        LongAdder fail = new LongAdder();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        long start = System.currentTimeMillis();

        for (int i = 0; i < concurrency; i++) {
            final int idx = i;
            pool.submit(() -> {
                PostingCommand cmd = new PostingCommand(
                        UUID.randomUUID().toString(),
                        "WITHDRAWAL",
                        "SA-" + idx,
                        LocalDate.of(2026, 5, 23),
                        List.of(new PostingCommand.Leg(
                                "leg-1",
                                "WITHDRAWAL",
                                new BigDecimal("1.00"),
                                "USD",
                                List.of(
                                        new PostingCommand.Line(sameAcc, "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "SA"),
                                        new PostingCommand.Line(hotspot, "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "SA")
                                )
                        ))
                );
                try {
                    CommandResult r = client.post(cmd);
                    if (r.isCompleted()) ok.increment();
                    else if (r.isRejected()) rej.increment();
                    else fail.increment();
                } catch (Exception e) {
                    fail.increment();
                }
            });
        }

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.MINUTES);
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("Race: ok=%d, rej=%d, fail=%d, time=%dms%n",
                ok.sum(), rej.sum(), fail.sum(), elapsed);
    }

    private static void runReadWrite(LedgerClient client, String hotspot, String prefix, int accountCount, int concurrency, int durationSec) throws Exception {
        System.out.println("--- Read/Write Phase ---");
        LongAdder wOk = new LongAdder();
        LongAdder wFail = new LongAdder();
        LongAdder rOk = new LongAdder();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        long end = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(durationSec);

        for (int i = 0; i < concurrency / 2; i++) {
            pool.submit(() -> {
                while (System.currentTimeMillis() < end) {
                    String acc = prefix + String.format("%04d", ThreadLocalRandom.current().nextInt(1, accountCount + 1));
                    PostingCommand cmd = new PostingCommand(
                            UUID.randomUUID().toString(),
                            "DEPOSIT",
                            "RW",
                            LocalDate.of(2026, 5, 23),
                            List.of(new PostingCommand.Leg(
                                    "leg-1",
                                    "DEPOSIT",
                                    new BigDecimal("0.01"),
                                    "USD",
                                    List.of(
                                            new PostingCommand.Line(hotspot, "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "RW"),
                                            new PostingCommand.Line(acc, "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "RW")
                                    )
                            ))
                    );
                    try {
                        CommandResult r = client.post(cmd);
                        if (r.isCompleted()) wOk.increment();
                        else wFail.increment();
                    } catch (Exception e) {
                        wFail.increment();
                    }
                }
            });
        }

        for (int i = 0; i < concurrency / 2; i++) {
            pool.submit(() -> {
                while (System.currentTimeMillis() < end) {
                    String acc = prefix + String.format("%04d", ThreadLocalRandom.current().nextInt(1, accountCount + 1));
                    try {
                        client.queryBalance(acc, "AVAILABLE_BALANCE", "USD");
                        rOk.increment();
                    } catch (Exception e) {
                        // ignore read failures
                    }
                }
            });
        }

        pool.shutdown();
        pool.awaitTermination(durationSec + 10, TimeUnit.SECONDS);
        System.out.printf("ReadWrite: writes=%d, writeFails=%d, reads=%d%n",
                wOk.sum(), wFail.sum(), rOk.sum());
    }
}
