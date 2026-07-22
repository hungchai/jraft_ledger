package com.tomma8.ledger.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EmitGate unit tests.
 */
@DisplayName("EmitGate")
class EmitGateTest {

    @Test
    @DisplayName("default state is closed")
    void defaultState_isClosed() {
        EmitGate gate = new EmitGate();
        assertThat(gate.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("setEnabled(true) opens the gate")
    void setEnabledTrue_opens() {
        EmitGate gate = new EmitGate();
        gate.setEnabled(true);
        assertThat(gate.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("setEnabled(false) closes the gate after open")
    void setEnabledFalse_closesAfterOpen() {
        EmitGate gate = new EmitGate();
        gate.setEnabled(true);
        gate.setEnabled(false);
        assertThat(gate.isEnabled()).isFalse();
    }
}
