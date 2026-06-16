package com.tomma8.ledger.domain.event;

import java.util.List;

/**
 * Envelope that bundles all BalanceChangeEvents for a single journal (posting
 * or reversal) into one Kafka record. Wire format:
 * <pre>
 * {
 *   "type": "JOURNAL",
 *   "journalId": "JNL-...",
 *   "events": [ BalanceChangeEvent, ... ]
 * }
 * </pre>
 *
 * Replaces the per-line one-Kafka-record-per-event approach. With this
 * envelope, a 4-line posting produces exactly 1 Kafka record instead of 4.
 * Consumer side (ProjectionConsumer) detects the envelope via the
 * {@code type} discriminator and processes the events array.
 */
public record JournalEventEnvelope(
        String type,
        String journalId,
        List<BalanceChangeEvent> events) {

    public static final String TYPE = "JOURNAL";

    public JournalEventEnvelope {
        events = List.copyOf(events);
    }
}
