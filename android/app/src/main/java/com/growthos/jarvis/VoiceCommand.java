package com.growthos.jarvis;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns recognized words into one allowed action. Pure Java, unit-tested. */
public final class VoiceCommand {
    public enum Type { OPEN_APP, STOP_LISTENING, CANCEL, UNKNOWN }

    public final Type type;
    public final String target;

    private VoiceCommand(Type type, String target) { this.type = type; this.target = target; }

    private static final Pattern OPEN = Pattern.compile("^(?:please\\s+)?(?:can you\\s+)?(?:open|launch|start|run|show)\\s+(?:up\\s+)?(.+?)(?:\\s+(?:app|application))?(?:\\s+please)?$");
    private static final Pattern OPEN_SUFFIX = Pattern.compile("^(.+?)\\s+(?:open|kholo|open karo|open cheyyi|open chey)$");
    private static final Pattern STOP = Pattern.compile("^(?:stop|turn off|disable)\\s+(?:listening|jarvis|kalki|wake word)$");
    private static final Pattern CANCEL = Pattern.compile("^(?:cancel|nothing|never mind|nevermind|no|stop)$");

    public static VoiceCommand parse(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[.!?,]+", " ").replaceAll("\\s+", " ").trim();
        t = t.replaceFirst("^(?:hey\\s+)?(?:jarvis|kalki)\\s+", "");
        if (t.isEmpty()) return new VoiceCommand(Type.UNKNOWN, "");
        if (STOP.matcher(t).matches()) return new VoiceCommand(Type.STOP_LISTENING, "");
        if (CANCEL.matcher(t).matches()) return new VoiceCommand(Type.CANCEL, "");
        Matcher m = OPEN.matcher(t);
        if (m.matches()) return new VoiceCommand(Type.OPEN_APP, m.group(1).trim());
        m = OPEN_SUFFIX.matcher(t);
        if (m.matches()) return new VoiceCommand(Type.OPEN_APP, m.group(1).trim());
        return new VoiceCommand(Type.UNKNOWN, t);
    }
}
