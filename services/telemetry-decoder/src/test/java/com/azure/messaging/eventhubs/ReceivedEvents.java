package com.azure.messaging.eventhubs;

import com.azure.core.util.Context;
import java.time.Instant;

/** Builds events as the processor delivers them (with system properties), which the public constructors cannot. */
public final class ReceivedEvents {

    private ReceivedEvents() {
    }

    public static EventData received(EventData sent, long sequenceNumber, Instant enqueuedTime) {
        return new EventData(sent.getRawAmqpMessage(),
                new SystemProperties(sent.getRawAmqpMessage(), String.valueOf(sequenceNumber * 100), enqueuedTime, sequenceNumber, null),
                Context.NONE);
    }
}
