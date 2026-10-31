package io.github.ikhemissi.sdp.deviceapi.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A validated query period {@code [from, to)}. */
public record Period(Instant from, Instant to) {

    /**
     * The requested period: {@code to} defaults to just after {@code now}, {@code from} to {@code defaultLength} before
     * {@code to}; rejected (400) when empty or longer than {@code maxLength}.
     */
    public static Period of(Instant from, Instant to, Instant now, Duration defaultLength, Duration maxLength) {
        Instant end = to != null ? to : now.plusSeconds(1);
        Instant start = from != null ? from : end.minus(defaultLength);
        if (!start.isBefore(end)) {
            throw ApiException.invalid(Map.of("from", List.of("must be before to")));
        }
        if (Duration.between(start, end).compareTo(maxLength) > 0) {
            throw ApiException.invalid(Map.of("from", List.of("the period is at most " + maxLength.toDays() + " days")));
        }
        return new Period(start, end);
    }
}
