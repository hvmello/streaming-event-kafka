package com.example.videostream.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranscodePropertiesTest {

    private static TranscodeProperties withLease(Duration lease) {
        return new TranscodeProperties(true, "ffmpeg", Duration.ofMinutes(30), 6, 1, Duration.ofMinutes(5), lease,
                null);
    }

    @Test
    void aPositiveLeaseIsAccepted() {
        assertThat(withLease(Duration.ofSeconds(60)).lease()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void aZeroLeaseIsRejected() {
        assertThatThrownBy(() -> withLease(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease");
    }

    @Test
    void aNegativeLeaseIsRejected() {
        assertThatThrownBy(() -> withLease(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingLeaseIsRejected() {
        assertThatThrownBy(() -> withLease(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
