package io.github.ikhemissi.sdp.alerts;

import io.github.ikhemissi.sdp.alerts.notifications.EmailGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A clock the test moves, and an email gateway that records emails or fails on demand. */
@TestConfiguration
public class TestDoubles {

    public static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        public MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        public void set(Instant instant) {
            now.set(instant);
        }

        public void advance(Duration duration) {
            now.updateAndGet(i -> i.plus(duration));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    public static final class RecordingEmails implements EmailGateway {
        public final List<Email> sent = new CopyOnWriteArrayList<>();
        public volatile boolean failing;

        @Override
        public void send(Email email) {
            if (failing) {
                throw new IllegalStateException("email service unavailable (test)");
            }
            sent.add(email);
        }
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(Instant.parse("2026-10-28T09:00:00Z"));
    }

    @Bean
    @Primary
    RecordingEmails recordingEmails() {
        return new RecordingEmails();
    }
}
