package com.growthos.jarvis;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns recognized words into one allowed action. Pure Java, unit-tested. */
public final class VoiceCommand {
    public enum Type { OPEN_APP, SEND_MESSAGE, STOP_LISTENING, CANCEL, UNKNOWN }

    public final Type type;
    /** For OPEN_APP the app name; for SEND_MESSAGE the person's name. */
    public final String target;
    /** For SEND_MESSAGE the words to send (may be empty). */
    public final String message;

    private VoiceCommand(Type type, String target) { this(type, target, ""); }
    private VoiceCommand(Type type, String target, String message) { this.type = type; this.target = target; this.message = message; }

    private static final String SAYING = "(?:\\s*,?\\s*(?:by\\s+)?(?:saying|say|says|that|telling|tell|as|with|message)\\s+)";
    // "send (a) (whatsapp) message to Ravi (by) saying hi", "message Ravi saying hi", "tell Ravi that hi"
    private static final Pattern SEND_TO = Pattern.compile("^(?:please\\s+)?(?:send|sent|write)\\s+(?:a\\s+)?(?:whatsapp\\s+|whats app\\s+)?(?:message|msg|text|massage|messages)\\s+(?:to|for)\\s+(.+?)(?:" + SAYING + "(.+))?$");
    private static final Pattern VERB_TO = Pattern.compile("^(?:please\\s+)?(?:message|text|whatsapp|whats app|tell|ping)\\s+(?:to\\s+)?(.+?)" + SAYING + "(.+)$");
    // "send hi to Ravi"
    private static final Pattern SEND_WORDS = Pattern.compile("^(?:please\\s+)?send\\s+(.+?)\\s+to\\s+(.+)$");

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
        String w = t.replaceFirst("\\s+(?:on|in|through|via|using)\\s+(?:whatsapp|whats app)$", "");
        Matcher m = SEND_TO.matcher(w);
        if (m.matches()) return send(m.group(1), m.group(2));
        m = VERB_TO.matcher(w);
        if (m.matches()) return send(m.group(1), m.group(2));
        m = SEND_WORDS.matcher(w);
        if (m.matches() && !m.group(1).matches("(?:a\\s+)?(?:whatsapp\\s+)?(?:message|msg|text)")) return send(m.group(2), m.group(1));
        m = OPEN.matcher(t);
        if (m.matches()) return new VoiceCommand(Type.OPEN_APP, m.group(1).trim());
        m = OPEN_SUFFIX.matcher(t);
        if (m.matches()) return new VoiceCommand(Type.OPEN_APP, m.group(1).trim());
        return new VoiceCommand(Type.UNKNOWN, t);
    }

    private static VoiceCommand send(String who, String what) {
        who = who.trim().replaceFirst("\\s+(?:on|in)\\s+whatsapp$", "");
        return new VoiceCommand(Type.SEND_MESSAGE, who, what == null ? "" : what.trim());
    }
}
