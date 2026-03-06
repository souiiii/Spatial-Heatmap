package com.heatmap.model;

/**
 * Represents the type of analytics event being recorded.
 */
public enum EventType {
    DEATH,
    BLOCK_BREAK,
    PLAYER_MOVE;

    /**
     * Parse a string to an EventType, case-insensitive.
     * Returns null if the string does not match any type.
     */
    public static EventType fromString(String s) {
        if (s == null)
            return null;
        try {
            return valueOf(s.toUpperCase().replace("-", "_").replace(" ", "_"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
