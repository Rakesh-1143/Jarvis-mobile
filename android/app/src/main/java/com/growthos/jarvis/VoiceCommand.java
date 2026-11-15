package com.growthos.jarvis;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns recognized words into one allowed action. Pure Java, unit-tested. */
public final class VoiceCommand {
    public enum Type { OPEN_APP, SEND_MESSAGE, SEARCH, SCROLL, PLAY_ITEM, MEDIA, BACK, CALL, CALL_CONTROL, TIME, DATE, BATTERY, VOLUME, TORCH, ALARM, TIMER, SETTINGS, ASK_APP,
        TAP, TYPE, HOME, RECENTS, NOTIFICATIONS, READ_SCREEN, STOP_LISTENING, CANCEL, UNKNOWN }

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
        VoiceCommand d = device(t);
        if (d != null) return d;
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

    // ---------- phone and screen commands ----------
    private static final Pattern CALL = Pattern.compile("^(?:make\\s+a\\s+)?(?:call|phone|dial|ring)\\s+(?:to\\s+)?(.+?)(?:\\s+(?:on|using)\\s+(?:phone|mobile|sim))?$");
    private static final Pattern ANSWER = Pattern.compile("^(?:answer|pick up|pickup|accept|attend|receive|lift)(?:\\s+(?:the|this))?(?:\\s+(?:call|phone))?$");
    private static final Pattern REJECT = Pattern.compile("^(?:reject|decline|ignore)(?:\\s+(?:the|this))?(?:\\s+call)?$");
    private static final Pattern END_CALL = Pattern.compile("^(?:end|cut|disconnect|stop)(?:\\s+(?:the|this))?\\s+call$|^hang\\s*up(?:\\s+the call)?$");
    private static final Pattern TIME = Pattern.compile("^(?:what(?:'s|s| is)?\\s+(?:the\\s+)?time(?:\\s+(?:is it|now))?|time(?:\\s+now)?|tell me the time|current time|what time is it(?:\\s+now)?)$");
    private static final Pattern DATE = Pattern.compile("^(?:what(?:'s|s| is)?\\s+(?:the\\s+|today's\\s+|todays\\s+)?(?:date|day)(?:\\s+today)?|today's date|todays date|what day is (?:it|today)|date)$");
    private static final Pattern BATTERY = Pattern.compile("^(?:.*\\bbattery\\b.*|how much charge.*|charge level)$");
    private static final Pattern VOLUME = Pattern.compile("^(?:(?:turn|make)\\s+(?:the\\s+)?)?(?:volume|sound)\\s+(up|down|louder|lower|higher|max|full|maximum|mute|off|on|unmute)$|^(increase|decrease|raise|lower|reduce|mute|unmute)\\s+(?:the\\s+)?(?:volume|sound)$|^(mute|unmute|louder|quieter)$");
    private static final Pattern TORCH = Pattern.compile("^(?:turn|switch|put)\\s+(on|off)\\s+(?:the\\s+)?(?:torch|flashlight|flash light|flash|light)$|^(?:turn|switch)?\\s*(?:the\\s+)?(?:torch|flashlight|flash light|flash)\\s+(on|off)$");
    private static final Pattern ALARM = Pattern.compile("^(?:set\\s+(?:an?\\s+|the\\s+)?alarm|wake me up|alarm)\\s+(?:for\\s+|at\\s+)?(?:tomorrow\\s+)?(?:at\\s+)?(\\d{1,2})(?:[:. ](\\d{2}))?\\s*(am|pm|a m|p m|in the morning|in the evening|at night)?(?:\\s+tomorrow)?$");
    private static final Pattern TIMER = Pattern.compile("^(?:set\\s+(?:an?\\s+)?)?timer\\s+(?:for\\s+)?(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)$|^(?:set\\s+(?:an?\\s+)?)?(\\d+)\\s*(seconds?|minutes?|mins?|hours?)\\s+timer$");
    private static final Pattern SETTINGS = Pattern.compile("^(?:open|show|go to)\\s+(?:the\\s+)?(wifi|wi-fi|wi fi|bluetooth|mobile data|data|hotspot|location|gps|display|brightness|sound|battery|apps|app|notification|notifications|storage|security|date and time|airplane mode|flight mode)\\s+settings?$|^(?:turn|switch)\\s+(?:on|off)\\s+(wifi|wi-fi|wi fi|bluetooth|mobile data|hotspot|location|gps|airplane mode|flight mode)$");
    private static final Pattern ASK_APP = Pattern.compile("^(?:ask|tell|message)\\s+(claude|cloud|chatgpt|chat gpt|gemini|meta ai|perplexity|copilot)\\s+(?:to\\s+|that\\s+|about\\s+)?(.+)$");
    private static final Pattern TAP = Pattern.compile("^(?:tap|click|press|touch|hit)\\s+(?:on\\s+)?(?:the\\s+)?(.+?)(?:\\s+(?:button|icon|option|tab|link))?$");
    private static final Pattern TYPE = Pattern.compile("^(?:type|enter)\\s+(.+)$");
    private static final Pattern HOME = Pattern.compile("^(?:go\\s+(?:to\\s+)?)?home(?:\\s+screen)?$|^(?:go to|show)\\s+(?:the\\s+)?home\\s+screen$|^minimi[sz]e(?:\\s+(?:this|the app|app))?$");
    private static final Pattern RECENTS = Pattern.compile("^(?:show\\s+|open\\s+)?(?:the\\s+)?recent(?:s| apps)?$");
    private static final Pattern NOTIFS = Pattern.compile("^(?:open|show|pull down)\\s+(?:my\\s+|the\\s+)?notifications?(?:\\s+panel)?$");
    private static final Pattern READ = Pattern.compile("^(?:read|read out)\\s+(?:the\\s+|this\\s+)?screen$|^what(?:'s|s| is)\\s+on\\s+(?:the\\s+|my\\s+)?screen$|^read (?:it|this)$");

    private static VoiceCommand device(String t) {
        Matcher m;
        if (ANSWER.matcher(t).matches()) return new VoiceCommand(Type.CALL_CONTROL, "answer");
        if (REJECT.matcher(t).matches()) return new VoiceCommand(Type.CALL_CONTROL, "reject");
        if (END_CALL.matcher(t).matches()) return new VoiceCommand(Type.CALL_CONTROL, "end");
        if (TIME.matcher(t).matches()) return new VoiceCommand(Type.TIME, "");
        if (DATE.matcher(t).matches()) return new VoiceCommand(Type.DATE, "");
        m = VOLUME.matcher(t);
        if (m.matches()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            String a = v.matches("up|louder|higher|increase|raise|on") ? "up" : v.matches("down|lower|decrease|reduce|quieter") ? "down"
                    : v.matches("max|full|maximum") ? "max" : v.equals("unmute") ? "unmute" : "mute";
            return new VoiceCommand(Type.VOLUME, a);
        }
        m = TORCH.matcher(t);
        if (m.matches()) return new VoiceCommand(Type.TORCH, m.group(1) != null ? m.group(1) : m.group(2));
        m = ALARM.matcher(t);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1)), min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            String ap = m.group(3) == null ? "" : m.group(3);
            if (ap.startsWith("p") || ap.contains("evening") || ap.contains("night")) { if (h < 12) h += 12; }
            else if (ap.startsWith("a") || ap.contains("morning")) { if (h == 12) h = 0; }
            if (h > 23 || min > 59) return null;
            return new VoiceCommand(Type.ALARM, String.format(Locale.ROOT, "%02d:%02d", h, min));
        }
        m = TIMER.matcher(t);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(3));
            String u = m.group(2) != null ? m.group(2) : m.group(4);
            int secs = u.startsWith("h") ? n * 3600 : u.startsWith("m") ? n * 60 : n;
            return new VoiceCommand(Type.TIMER, String.valueOf(secs));
        }
        m = SETTINGS.matcher(t);
        if (m.matches()) return new VoiceCommand(Type.SETTINGS, (m.group(1) != null ? m.group(1) : m.group(2)).replace("wi-fi", "wifi").replace("wi fi", "wifi"));
        if (BATTERY.matcher(t).matches()) return new VoiceCommand(Type.BATTERY, "");
        m = ASK_APP.matcher(t);
        if (m.matches()) return new VoiceCommand(Type.ASK_APP, m.group(1).equals("cloud") ? "claude" : m.group(1), m.group(2).trim());
        if (HOME.matcher(t).matches()) return new VoiceCommand(Type.HOME, "");
        if (RECENTS.matcher(t).matches()) return new VoiceCommand(Type.RECENTS, "");
        if (NOTIFS.matcher(t).matches()) return new VoiceCommand(Type.NOTIFICATIONS, "");
        if (READ.matcher(t).matches()) return new VoiceCommand(Type.READ_SCREEN, "");
        m = TYPE.matcher(t);
        if (m.matches()) return new VoiceCommand(Type.TYPE, "", m.group(1).trim());
        m = TAP.matcher(t);
        if (m.matches()) return new VoiceCommand(Type.TAP, m.group(1).trim());
        m = CALL.matcher(t);
        if (m.matches() && !m.group(1).matches("(?:me|back|later)")) return new VoiceCommand(Type.CALL, m.group(1).trim());
        return null;
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
