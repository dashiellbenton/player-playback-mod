package com.playerplayback.util;

import java.util.regex.Pattern;

public final class Names {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_.-]{1,48}");

    private Names() {}

    public static boolean valid(String name) {
        return name != null && VALID.matcher(name).matches() && !name.startsWith(".");
    }

    /** Minecraft player names are at most 16 characters. */
    public static String profileName(String name) {
        if (name == null || name.isBlank()) return "Actor";
        return name.length() > 16 ? name.substring(0, 16) : name;
    }
}
