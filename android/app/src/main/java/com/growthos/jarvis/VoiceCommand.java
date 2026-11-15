package com.growthos.jarvis;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns recognized words into one allowed action. Pure Java, unit-tested. */
public final class VoiceCommand {
    public enum Type { OPEN_APP, SEND_MESSAGE, SEARCH, SCROLL, PLAY_ITEM, MEDIA, BACK, STOP_LISTENING, CANCEL, UNKNOWN }

    public final Type type;
    /** For OPEN_APP the app name; for SEND_MESSAGE the person's name; for SEARCH the app ("" = web). */
    public final String target;
    /** For SEND_MESSAGE the words to send (may be empty); for SEARCH what to search for. */
    public final String message;

    private VoiceCommand(Type type, String target) { this(type, target, ""); }
    private VoiceCommand(Type type, String target, String message) { this.type = type; this.target = target; this.message = message; }

    private static final String SAYING = "(?:\\s*,?\\s*(?:by\\s+)?(?:saying|say|says|that|telling|tell|as|with|message)\\s+)";
    // "send (a) (whatsapp) message to Ravi (by) saying hi", "message Ravi saying hi", "tell Ravi that hi"
    private static final Pattern SEND_TO = Pattern.compile("^(?:please\\s+)?(?:send|sent|write)\\s+(?:a\\s+)?(?:whatsapp\\s+|whats app\\s+)?(?:message|msg|text|massage|messages)\\s+(?:to|for)\\s+(.+?)(?:" + SAYING + "(.+))?$");
    private static final Pattern VERB_TO = Pattern.compile("^(?:please\\s+)?(?:message|text|whatsapp|whats app|tell|ping)\\s+(?:to\\s+)?(.+?)" + SAYING + "(.+)$");
    private static final String IN_APP = "\\s+(?:in|on|from|at|using|inside)\\s+(?:the\\s+)?(.+?)(?:\\s+(?:app|application))?";
    // "search latest movies on YouTube", "play Leo songs on YouTube", "find pizza near me in maps"
    private static final Pattern SEARCH_IN = Pattern.compile("^(?:please\\s+)?(?:can you\\s+)?(?:search|search for|find|look up|look for|play|show me|show|watch)\\s+(?:for\\s+)?(.+?)" + IN_APP + "$");
    // "open YouTube and search latest movies", "YouTube search latest movies"
    private static final Pattern OPEN_AND_SEARCH = Pattern.compile("^(?:(?:open|go to|launch)\\s+)?(.+?)(?:\\s+and\\s+(?:search|find|play|look for)|\\s+search)\\s+(?:for\\s+)?(.+)$");
    // "search latest movies" (web)
    private static final Pattern SEARCH_WEB = Pattern.compile("^(?:please\\s+)?(?:search|search for|google|look up)\\s+(?:for\\s+)?(.+)$");
    /** Apps where "X in <app>" alone clearly means a search. */
    private static final Pattern SEARCHABLE = Pattern.compile("^(?:youtube|you tube|google|chrome|play store|maps|google maps|spotify|amazon|flipkart)$");
    private static final Pattern BARE_IN_APP = Pattern.compile("^(.+?)" + IN_APP + "$");
    private static final String POLITE = "(?:please|pls|can you|could you|will you|would you|can u|i want to|i want you to|i need to|i would like to|just|boss|now|ok|okay)";
    private static final Pattern OPEN_WHATSAPP_THEN = Pattern.compile("^(?:open|go to|launch|start)\\s+(?:whatsapp|whats app)\\s+(?:and\\s+|then\\s+|and then\\s+)?(.+)$");
    // after "open WhatsApp and": "find the name Ravi", "open Ravi chat", "go to chat with Ravi"
    private static final Pattern FIND_CHAT = Pattern.compile("^(?:find|search|search for|open|go to|look for|check)\\s+(?:the\\s+)?(?:name\\s+|contact\\s+|chat\\s+(?:with\\s+|of\\s+)?)?(.+?)(?:\\s+(?:chat|contact))?$");
    // "send Ravi a message saying hi", "send Ravi a message"
    private static final Pattern SEND_NAME_FIRST = Pattern.compile("^send\\s+(.+?)\\s+a\\s+(?:whatsapp\\s+)?(?:message|msg|text)(?:" + SAYING + "(.+))?$");
    // "send hi to Ravi"
    private static final Pattern SEND_WORDS = Pattern.compile("^(?:please\\s+)?send\\s+(.+?)\\s+to\\s+(.+)$");

    private static final Pattern OPEN = Pattern.compile("^(?:please\\s+)?(?:can you\\s+)?(?:open|launch|start|run|show)\\s+(?:up\\s+)?(.+?)(?:\\s+(?:app|application))?(?:\\s+please)?$");
    private static final Pattern OPEN_SUFFIX = Pattern.compile("^(.+?)\\s+(?:open|kholo|open karo|open cheyyi|open chey)$");
    private static final Pattern STOP = Pattern.compile("^(?:stop|turn off|disable)\\s+(?:listening|jarvis|kalki|wake word)$");
    private static final Pattern SCROLL = Pattern.compile("^(?:scroll|swipe|go|move)\\s*(down|up|more|further|below|above|back up)?(?:\\s+(?:more|again|a bit|little|please|the page|the list))*$|^(?:show more|next page|more videos|more results)$");
    private static final String ORDINAL = "(first|second|third|fourth|fifth|sixth|1st|2nd|3rd|4th|5th|6th|one|two|three|four|five|six|[1-6]|top|this|that|the)";
    private static final Pattern PLAY_ITEM = Pattern.compile("^(?:play|open|start|click|tap|watch|select)(?:\\s+on)?(?:\\s+the)?(?:\\s+" + ORDINAL + ")?\\s+(?:video|result|one|link)(?:\\s+(?:number\\s+)?" + ORDINAL + ")?(?:\\s+please)?$|^(?:play|open|watch)\\s+(?:the\\s+)?" + ORDINAL + "\\s+one$");
    private static final Pattern MEDIA = Pattern.compile("^(pause|resume|continue|play|stop|next|skip|previous|replay)(?:\\s+(?:the\\s+)?(?:video|song|music|it|playing|this))?(?:\\s+(?:video|song))?(?:\\s+please)?$|^(?:play|go to)\\s+(?:the\\s+)?(next|previous)\\s+(?:video|song)$");
    private static final Pattern BACK = Pattern.compile("^(?:go\\s+)?back(?:\\s+please)?$");
    private static final Pattern CANCEL = Pattern.compile("^(?:cancel|nothing|never mind|nevermind|no|stop)$");

    public static VoiceCommand parse(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[.!?,]+", " ").replaceAll("\\s+", " ").trim();
        t = t.replaceFirst("^(?:hey\\s+)?(?:jarvis|kalki)\\s+", "");
        // Polite starts: "can you", "please", "I want to", "boss" ...
        String before;
        do { before = t; t = t.replaceFirst("^" + POLITE + "\\s+", ""); } while (!t.equals(before));
        if (t.isEmpty()) return new VoiceCommand(Type.UNKNOWN, "");
        if (STOP.matcher(t).matches()) return new VoiceCommand(Type.STOP_LISTENING, "");
        if (CANCEL.matcher(t).matches()) return new VoiceCommand(Type.CANCEL, "");
        Matcher s0 = SCROLL.matcher(t);
        if (s0.matches()) {
            String d = s0.group(1) == null ? "down" : s0.group(1);
            return new VoiceCommand(Type.SCROLL, d.equals("up") || d.equals("above") || d.equals("back up") ? "up" : "down");
        }
        s0 = PLAY_ITEM.matcher(t);
        if (s0.matches()) {
            String o = s0.group(1) != null ? s0.group(1) : s0.group(2) != null ? s0.group(2) : s0.group(3);
            return new VoiceCommand(Type.PLAY_ITEM, String.valueOf(ordinal(o)));
        }
        s0 = MEDIA.matcher(t);
        if (s0.matches()) {
            String a = s0.group(1) != null ? s0.group(1) : s0.group(2);
            String key = a.equals("pause") || a.equals("stop") ? "pause" : a.equals("next") || a.equals("skip") ? "next"
                    : a.equals("previous") || a.equals("replay") ? "previous" : "play";
            return new VoiceCommand(Type.MEDIA, key);
        }
        if (BACK.matcher(t).matches()) return new VoiceCommand(Type.BACK, "");
        String w = t.replaceFirst("\\s+(?:on|in|through|via|using)\\s+(?:whatsapp|whats app)$", "");
        // "open WhatsApp (and) send message to Ravi saying hi" -> "send message to Ravi saying hi"
        Matcher m = OPEN_WHATSAPP_THEN.matcher(w);
        if (m.matches()) {
            w = m.group(1);
            Matcher f = FIND_CHAT.matcher(w);
            if (f.matches()) return send(f.group(1), "");
        }
        m = SEND_TO.matcher(w);
        if (m.matches()) return send(m.group(1), m.group(2));
        m = VERB_TO.matcher(w);
        if (m.matches()) return send(m.group(1), m.group(2));
        m = SEND_WORDS.matcher(w);
        if (m.matches()) {
            String words = m.group(1).replaceFirst("^(?:a\\s+)?(?:whatsapp\\s+|whats app\\s+)?(?:message|msg|text|massage)(?:\\s+|$)", "")
                    .replaceFirst("^(?:saying|that)\\s+", "");
            return send(m.group(2), words);
        }
        m = SEND_NAME_FIRST.matcher(w);
        if (m.matches()) return send(m.group(1), m.group(2) == null ? "" : m.group(2));
        m = SEARCH_IN.matcher(w);
        if (m.matches()) return search(m.group(2), m.group(1));
        m = OPEN_AND_SEARCH.matcher(w);
        if (m.matches() && m.group(1).split(" ").length <= 3) return search(m.group(1), m.group(2));
        m = SEARCH_WEB.matcher(w);
        if (m.matches()) return search("", m.group(1));
        m = BARE_IN_APP.matcher(w);
        if (m.matches() && SEARCHABLE.matcher(m.group(2)).matches()) return search(m.group(2), m.group(1));
        m = OPEN.matcher(t);
        if (m.matches()) return new VoiceCommand(Type.OPEN_APP, m.group(1).trim());
        m = OPEN_SUFFIX.matcher(t);
        if (m.matches()) return new VoiceCommand(Type.OPEN_APP, m.group(1).trim());
        return new VoiceCommand(Type.UNKNOWN, t);
    }

    private static final Pattern YES = Pattern.compile("^(?:yes|yeah|yep|yup|ya|haan|ha|han|ok|okay|sure|send|send it|yes send|yes send it|go ahead|do it|correct|right)(?:\\s+(?:please|boss|send it|send))*$");
    private static final Pattern NO = Pattern.compile("^(?:no|nope|nah|don't|do not|cancel|stop|wait|don't send|do not send|leave it|nahi)(?:\\s+.*)?$");

    /** A clear spoken yes. Anything unclear counts as no, so nothing is sent by mistake. */
    public static boolean isYes(String text) { return YES.matcher(clean(text)).matches(); }
    public static boolean isNo(String text) { return NO.matcher(clean(text)).matches(); }
    private static String clean(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[.!?,]+", " ").replaceAll("\\s+", " ").trim();
    }

    /** "second" -> 2; missing or "this/top" -> 1. */
    static int ordinal(String o) {
        if (o == null) return 1;
        switch (o) {
            case "second": case "2nd": case "two": case "2": return 2;
            case "third": case "3rd": case "three": case "3": return 3;
            case "fourth": case "4th": case "four": case "4": return 4;
            case "fifth": case "5th": case "five": case "5": return 5;
            case "sixth": case "6th": case "six": case "6": return 6;
            default: return 1;
        }
    }

    private static VoiceCommand search(String app, String query) {
        return new VoiceCommand(Type.SEARCH, app.trim(), query.trim());
    }

    private static VoiceCommand send(String who, String what) {
        who = who.trim().replaceFirst("\\s+(?:on|in)\\s+whatsapp$", "");
        return new VoiceCommand(Type.SEND_MESSAGE, who, what == null ? "" : what.trim());
    }
}
