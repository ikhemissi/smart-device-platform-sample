package io.github.ikhemissi.sdp.deviceapi.web;

import java.util.Arrays;

/** Optimistic concurrency with entity tags ({@code ETag} / {@code If-Match}, RFC 9110). */
public final class EntityTags {

    private EntityTags() {
    }

    /** Whether an {@code If-Match} header matches the entity tag; weak tags compare by value, {@code *} matches any. */
    public static boolean matches(String ifMatch, String entityTag) {
        return Arrays.stream(ifMatch.split(","))
                .map(String::strip)
                .map(tag -> tag.startsWith("W/") ? tag.substring(2) : tag)
                .anyMatch(tag -> tag.equals("*") || tag.equals(entityTag));
    }
}
