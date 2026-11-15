package com.growthos.jarvis;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class VoiceCommandTest {
    private static final List<AppMatcher.App> APPS = Arrays.asList(
            new AppMatcher.App("WhatsApp", "com.whatsapp"),
            new AppMatcher.App("YouTube", "com.google.android.youtube"),
            new AppMatcher.App("YouTube Music", "com.google.android.apps.youtube.music"),
            new AppMatcher.App("Chrome", "com.android.chrome"),
            new AppMatcher.App("Play Store", "com.android.vending"),
            new AppMatcher.App("Maps", "com.google.android.apps.maps"),
            new AppMatcher.App("Photos", "com.google.android.apps.photos"),
            new AppMatcher.App("Phone", "com.google.android.dialer"),
            new AppMatcher.App("Messages", "com.google.android.apps.messaging"),
            new AppMatcher.App("Calculator", "com.google.android.calculator"),
            new AppMatcher.App("Settings", "com.android.settings"),
            new AppMatcher.App("Gmail", "com.google.android.gm"),
            new AppMatcher.App("Instagram", "com.instagram.android"),
            new AppMatcher.App("Camera", "com.motorola.camera3"),
            new AppMatcher.App("Claude", "com.anthropic.claude"),
            new AppMatcher.App("Clock", "com.google.android.deskclock"));

    private static final List<AppMatcher.App> PEOPLE = Arrays.asList(
            new AppMatcher.App("Ravi", "919000000001"),
            new AppMatcher.App("Ravi Teja College", "919000000002"),
            new AppMatcher.App("Amma", "919000000003"),
            new AppMatcher.App("Kiran Kumar", "919000000004"));

    private static VoiceCommand send(String said, String who, String what) {
        VoiceCommand c = VoiceCommand.parse(said);
        assertEquals(said, VoiceCommand.Type.SEND_MESSAGE, c.type);
        assertEquals(said, who, c.target);
        assertEquals(said, what, c.message);
        return c;
    }

    @Test public void opensClaudeEvenWhenHeardAsCloud() {
        assertEquals("com.anthropic.claude", open("open claude"));
        assertEquals("com.anthropic.claude", open("open cloud"));
        assertEquals("com.anthropic.claude", open("open claud app"));
        assertEquals("com.google.android.deskclock", open("open clock"));
    }

    @Test public void parsesSendMessage() {
        send("send message to ravie by saying hi", "ravie", "hi");
        send("Send a message to Ravi saying I will be late.", "ravi", "i will be late");
        send("send whatsapp message to amma that I reached home", "amma", "i reached home");
        send("message Kiran saying call me", "kiran", "call me");
        send("tell amma that I am coming", "amma", "i am coming");
        send("send hi to ravi on whatsapp", "ravi", "hi");
        send("send message to ravi", "ravi", "");
        assertEquals(VoiceCommand.Type.OPEN_APP, VoiceCommand.parse("open messages").type);
    }

    @Test public void findsContactsFromHeardNames() {
        assertEquals("919000000001", AppMatcher.matchName("ravie", PEOPLE).packageName);
        assertEquals("919000000001", AppMatcher.matchName("Ravi", PEOPLE).packageName);
        assertEquals("919000000003", AppMatcher.matchName("amma", PEOPLE).packageName);
        assertEquals("919000000004", AppMatcher.matchName("kiran", PEOPLE).packageName);
        assertNull(AppMatcher.matchName("suresh", PEOPLE));
    }

    private static String open(String said) {
        VoiceCommand c = VoiceCommand.parse(said);
        assertEquals(said, VoiceCommand.Type.OPEN_APP, c.type);
        AppMatcher.App a = AppMatcher.match(c.target, APPS);
        return a == null ? null : a.packageName;
    }

    @Test public void opensCommonApps() {
        assertEquals("com.whatsapp", open("Open WhatsApp"));
        assertEquals("com.whatsapp", open("open whats app"));
        assertEquals("com.whatsapp", open("Open what's app."));
        assertEquals("com.google.android.youtube", open("open YouTube"));
        assertEquals("com.google.android.youtube", open("launch you tube please"));
        assertEquals("com.google.android.apps.youtube.music", open("open youtube music"));
        assertEquals("com.android.chrome", open("open google chrome"));
        assertEquals("com.android.vending", open("open play store"));
        assertEquals("com.google.android.apps.maps", open("open google maps"));
        assertEquals("com.google.android.apps.photos", open("open gallery"));
        assertEquals("com.google.android.dialer", open("open phone"));
        assertEquals("com.google.android.apps.messaging", open("open messages app"));
        assertEquals("com.google.android.calculator", open("open calculator"));
        assertEquals("com.android.settings", open("open settings"));
        assertEquals("com.google.android.gm", open("open gmail"));
        assertEquals("com.instagram.android", open("open insta"));
        assertEquals("com.motorola.camera3", open("open the camera"));
        assertEquals("com.whatsapp", open("Hey Jarvis open WhatsApp"));
        assertEquals("com.whatsapp", open("whatsapp open"));
    }

    @Test public void missingAppIsNull() {
        assertNull(open("open spotify"));
        assertNull(open("open telegram"));
    }

    @Test public void otherCommands() {
        assertEquals(VoiceCommand.Type.STOP_LISTENING, VoiceCommand.parse("stop listening").type);
        assertEquals(VoiceCommand.Type.CANCEL, VoiceCommand.parse("never mind").type);
        assertEquals(VoiceCommand.Type.WEATHER, VoiceCommand.parse("what is the weather").type);
        assertEquals(VoiceCommand.Type.UNKNOWN, VoiceCommand.parse("").type);
    }

    @Test public void similarity() {
        assertTrue(AppMatcher.similarity("whatsapp", "watsapp") >= 0.8);
        assertTrue(AppMatcher.similarity("chrome", "camera") < 0.8);
    }

    private static void search(String said, String app, String query) {
        VoiceCommand c = VoiceCommand.parse(said);
        assertEquals(said, VoiceCommand.Type.SEARCH, c.type);
        assertEquals(said, app, c.target);
        assertEquals(said, query, c.message);
    }

    @Test public void parsesSearch() {
        search("search latest movies on YouTube", "youtube", "latest movies");
        search("search for latest movies in YouTube", "youtube", "latest movies");
        search("new latest movies in YouTube", "youtube", "new latest movies");
        search("play Leo songs on YouTube", "youtube", "leo songs");
        search("open YouTube and search latest movies", "youtube", "latest movies");
        search("YouTube search latest movies", "youtube", "latest movies");
        search("find pizza near me in maps", "maps", "pizza near me");
        search("search whatsapp web in play store", "play store", "whatsapp web");
        search("search weather today", "", "weather today");
        assertEquals(VoiceCommand.Type.OPEN_APP, VoiceCommand.parse("open play store").type);
        assertEquals(VoiceCommand.Type.OPEN_APP, VoiceCommand.parse("open jio hotstar").type);
        assertEquals(VoiceCommand.Type.SEND_MESSAGE, VoiceCommand.parse("send message to ravi saying I am in YouTube").type);
    }

    @Test public void understandsYesAndNo() {
        for (String y : new String[]{"yes", "Yes.", "yeah", "yes send it", "send it", "okay", "haan", "send"}) assertTrue(y, VoiceCommand.isYes(y));
        for (String n : new String[]{"no", "don't send", "cancel", "wait", "yes no", "send it to amma", "", "hmm"}) assertFalse(n, VoiceCommand.isYes(n));
        assertTrue(VoiceCommand.isNo("no don't send"));
    }

    @Test public void bareAppNames() {
        java.util.List<AppMatcher.App> apps = new java.util.ArrayList<>(APPS);
        apps.add(new AppMatcher.App("JioHotstar", "in.startv.hotstar"));
        assertEquals("in.startv.hotstar", AppMatcher.match("hotstar", apps).packageName);
        assertEquals("in.startv.hotstar", AppMatcher.match("jio hotstar", apps).packageName);
    }

    @Test public void parsesNaturalWhatsAppPhrasings() {
        send("Open WhatsApp send message to Ravi saying hi", "ravi", "hi");
        send("can you open WhatsApp and send message hi to Ravi", "ravi", "hi");
        send("can you send message to Ravi saying hi", "ravi", "hi");
        send("open WhatsApp and find the name Ravi", "ravi", "");
        send("open whatsapp and open ravi chat", "ravi", "");
        send("send message hi to Ravi", "ravi", "hi");
        send("please send Ravi a message saying good morning", "ravi", "good morning");
        send("I want to send a message to Amma", "amma", "");
        send("Jarvis can you please tell Kiran that I am late", "kiran", "i am late");
        assertEquals(VoiceCommand.Type.OPEN_APP, VoiceCommand.parse("can you open WhatsApp").type);
        assertEquals(VoiceCommand.Type.OPEN_APP, VoiceCommand.parse("open whatsapp").type);
    }

    private static void is(String said, VoiceCommand.Type type, String target) {
        VoiceCommand c = VoiceCommand.parse(said);
        assertEquals(said, type, c.type);
        assertEquals(said, target, c.target);
    }

    @Test public void parsesScreenAndMediaCommands() {
        is("scroll", VoiceCommand.Type.SCROLL, "down");
        is("scroll down", VoiceCommand.Type.SCROLL, "down");
        is("Scroll up please", VoiceCommand.Type.SCROLL, "up");
        is("can you scroll down more", VoiceCommand.Type.SCROLL, "down");
        is("show more", VoiceCommand.Type.SCROLL, "down");
        is("play video", VoiceCommand.Type.PLAY_ITEM, "1");
        is("play the first video", VoiceCommand.Type.PLAY_ITEM, "1");
        is("play the second video", VoiceCommand.Type.PLAY_ITEM, "2");
        is("open third video", VoiceCommand.Type.PLAY_ITEM, "3");
        is("play video number 2", VoiceCommand.Type.PLAY_ITEM, "2");
        is("play this video", VoiceCommand.Type.PLAY_ITEM, "1");
        is("play the second one", VoiceCommand.Type.PLAY_ITEM, "2");
        is("pause", VoiceCommand.Type.MEDIA, "pause");
        is("pause the video", VoiceCommand.Type.MEDIA, "pause");
        is("stop video", VoiceCommand.Type.MEDIA, "pause");
        is("play", VoiceCommand.Type.MEDIA, "play");
        is("resume", VoiceCommand.Type.MEDIA, "play");
        is("next video", VoiceCommand.Type.MEDIA, "next");
        is("play the next video", VoiceCommand.Type.MEDIA, "next");
        is("go back", VoiceCommand.Type.BACK, "");
        is("stop", VoiceCommand.Type.CANCEL, "");
        search("search for latest movies", "", "latest movies");
        search("play Leo songs on YouTube", "youtube", "leo songs");
        assertEquals(VoiceCommand.Type.OPEN_APP, VoiceCommand.parse("open YouTube").type);
    }

    @Test public void parsesPhoneAndScreenCommands() {
        is("call Ravi", VoiceCommand.Type.CALL, "ravi");
        is("can you call amma", VoiceCommand.Type.CALL, "amma");
        is("dial 98765 43210", VoiceCommand.Type.CALL, "98765 43210");
        is("answer the call", VoiceCommand.Type.CALL_CONTROL, "answer");
        is("pick up", VoiceCommand.Type.CALL_CONTROL, "answer");
        is("reject call", VoiceCommand.Type.CALL_CONTROL, "reject");
        is("end call", VoiceCommand.Type.CALL_CONTROL, "end");
        is("hang up", VoiceCommand.Type.CALL_CONTROL, "end");
        is("what time is it", VoiceCommand.Type.TIME, "");
        is("what's the date today", VoiceCommand.Type.DATE, "");
        is("how much battery is left", VoiceCommand.Type.BATTERY, "");
        is("volume up", VoiceCommand.Type.VOLUME, "up");
        is("decrease the volume", VoiceCommand.Type.VOLUME, "down");
        is("mute", VoiceCommand.Type.VOLUME, "mute");
        is("turn on the torch", VoiceCommand.Type.TORCH, "on");
        is("flashlight off", VoiceCommand.Type.TORCH, "off");
        is("set alarm for 6 am", VoiceCommand.Type.ALARM, "06:00");
        is("set an alarm at 6:30 pm", VoiceCommand.Type.ALARM, "18:30");
        is("wake me up at 7 30", VoiceCommand.Type.ALARM, "07:30");
        is("set timer for 10 minutes", VoiceCommand.Type.TIMER, "600");
        is("5 minute timer", VoiceCommand.Type.TIMER, "300");
        is("open wifi settings", VoiceCommand.Type.SETTINGS, "wifi");
        is("turn on bluetooth", VoiceCommand.Type.SETTINGS, "bluetooth");
        is("open battery settings", VoiceCommand.Type.SETTINGS, "battery");
        is("go home", VoiceCommand.Type.HOME, "");
        is("show recent apps", VoiceCommand.Type.RECENTS, "");
        is("open notifications", VoiceCommand.Type.NOTIFICATIONS, "");
        is("read the screen", VoiceCommand.Type.READ_SCREEN, "");
        is("tap search", VoiceCommand.Type.TAP, "search");
        is("click on the like button", VoiceCommand.Type.TAP, "like");
        VoiceCommand t = VoiceCommand.parse("type good morning");
        assertEquals(VoiceCommand.Type.TYPE, t.type);
        assertEquals("good morning", t.message);
        VoiceCommand a = VoiceCommand.parse("ask Claude what is the capital of France");
        assertEquals(VoiceCommand.Type.ASK_APP, a.type);
        assertEquals("claude", a.target);
        assertEquals("what is the capital of france", a.message);
        assertEquals("claude", VoiceCommand.parse("ask cloud to write a poem").target);
        // Older commands still work.
        is("tell amma that I am coming", VoiceCommand.Type.SEND_MESSAGE, "amma");
        is("open WhatsApp", VoiceCommand.Type.OPEN_APP, "whatsapp");
        is("play the second video", VoiceCommand.Type.PLAY_ITEM, "2");
        is("pause", VoiceCommand.Type.MEDIA, "pause");
        send("write message to ravi saying hi", "ravi", "hi");
    }

    @Test public void findsNamesInsideStrayWords() {
        java.util.List<AppMatcher.App> people = new java.util.ArrayList<>(PEOPLE);
        people.add(new AppMatcher.App("Moulali", "919000000005"));
        VoiceCommand c = VoiceCommand.parse("call tu moulali ence");
        assertEquals(VoiceCommand.Type.CALL, c.type);
        assertEquals("919000000005", AppMatcher.matchName(c.target, people).packageName);
        assertEquals("919000000001", AppMatcher.matchName("to ravi", people).packageName);
        assertNull(AppMatcher.matchName("tu suresh ence", people));
        is("what is my battery percentage", VoiceCommand.Type.BATTERY, "");
    }

    @Test public void parsesNotificationsAndQuestions() {
        is("read my notifications", VoiceCommand.Type.READ_NOTIFICATIONS, "");
        is("any new messages", VoiceCommand.Type.READ_NOTIFICATIONS, "");
        is("read my whatsapp messages", VoiceCommand.Type.READ_NOTIFICATIONS, "whatsapp");
        is("check notifications from instagram", VoiceCommand.Type.READ_NOTIFICATIONS, "instagram");
        is("do i have any new messages", VoiceCommand.Type.READ_NOTIFICATIONS, "");
        assertEquals(VoiceCommand.Type.UNKNOWN, VoiceCommand.parse("who is the prime minister of india").type);
        assertEquals(VoiceCommand.Type.UNKNOWN, VoiceCommand.parse("tell me a joke").type);
        assertEquals(VoiceCommand.Type.NOTIFICATIONS, VoiceCommand.parse("open notifications").type);
        assertEquals("hello world", GeminiClient.clean("**hello** _world_"));
    }

    private static void both(String said, VoiceCommand.Type type, String target, String message) {
        VoiceCommand c = VoiceCommand.parse(said);
        assertEquals(said, type, c.type);
        assertEquals(said, target, c.target);
        assertEquals(said, message, c.message);
    }

    @Test public void parsesBatchThree() {
        both("whatsapp video call ravi", VoiceCommand.Type.WHATSAPP_CALL, "ravi", "video");
        both("whatsapp call amma", VoiceCommand.Type.WHATSAPP_CALL, "amma", "voice");
        both("video call kiran on whatsapp", VoiceCommand.Type.WHATSAPP_CALL, "kiran", "video");
        both("call ravi on whatsapp", VoiceCommand.Type.WHATSAPP_CALL, "ravi", "voice");
        both("remind me to drink water at 5 pm", VoiceCommand.Type.REMINDER, "17:00", "drink water");
        both("remind me at 6 30 am to go for a walk", VoiceCommand.Type.REMINDER, "06:30", "go for a walk");
        both("remind me to call amma in 10 minutes", VoiceCommand.Type.REMINDER, "+600", "call amma");
        both("remind me in 2 hours to check the oven", VoiceCommand.Type.REMINDER, "+7200", "check the oven");
        both("take a note buy milk and eggs", VoiceCommand.Type.NOTE, "", "buy milk and eggs");
        both("note down meeting at 4", VoiceCommand.Type.NOTE, "", "meeting at 4");
        is("read my notes", VoiceCommand.Type.READ_NOTES, "");
        is("delete all my notes", VoiceCommand.Type.CLEAR_NOTES, "");
        is("navigate to charminar", VoiceCommand.Type.NAVIGATE, "charminar");
        is("take me to the airport", VoiceCommand.Type.NAVIGATE, "the airport");
        is("how do i get to hitech city", VoiceCommand.Type.NAVIGATE, "hitech city");
        is("what's the weather", VoiceCommand.Type.WEATHER, "");
        is("what's the weather in chennai", VoiceCommand.Type.WEATHER, "chennai");
        is("will it rain today", VoiceCommand.Type.WEATHER, "");
        is("take a photo", VoiceCommand.Type.PHOTO, "photo");
        is("take a selfie", VoiceCommand.Type.PHOTO, "selfie");
        is("what can you do", VoiceCommand.Type.HELP, "");
        is("call me rakesh", VoiceCommand.Type.SET_NAME, "rakesh");
        both("my city is warangal", VoiceCommand.Type.SET_NAME, "warangal", "city");
        is("speak slower", VoiceCommand.Type.SPEECH_SPEED, "slower");
        // Older commands unchanged.
        is("call ravi", VoiceCommand.Type.CALL, "ravi");
        is("set alarm for 6 am", VoiceCommand.Type.ALARM, "06:00");
        is("open whatsapp", VoiceCommand.Type.OPEN_APP, "whatsapp");
        assertEquals("raining", Weather.describe(63));
    }

    @Test
    public void brainChoicesAreChecked() {
        VoiceCommand c = VoiceCommand.fromBrain("open_app", "Instagram", "");
        assertEquals(VoiceCommand.Type.OPEN_APP, c.type);
        assertEquals("Instagram", c.target);
        c = VoiceCommand.fromBrain("SEND_MESSAGE", "Ravi", "I will be late");
        assertEquals(VoiceCommand.Type.SEND_MESSAGE, c.type);
        assertEquals("I will be late", c.message);
        assertEquals("07:05", VoiceCommand.fromBrain("ALARM", "7:05", "").target);
        assertEquals("+600", VoiceCommand.fromBrain("REMINDER", "+600", "drink water").target);
        assertEquals("18:00", VoiceCommand.fromBrain("REMINDER", "18:00", "call mom").target);
        assertEquals("300", VoiceCommand.fromBrain("TIMER", "300", "").target);
        assertEquals("claude", VoiceCommand.fromBrain("ASK_APP", "Cloud", "write a poem").target);
        assertEquals("video", VoiceCommand.fromBrain("WHATSAPP_CALL", "Ravi", "VIDEO").message);
        assertEquals("wifi", VoiceCommand.fromBrain("SETTINGS", "Wi-Fi", "").target);
        assertEquals("down", VoiceCommand.fromBrain("SCROLL", "", "").target);
        assertEquals("2", VoiceCommand.fromBrain("PLAY_ITEM", "2", "").target);
        assertEquals(VoiceCommand.Type.TIME, VoiceCommand.fromBrain("TIME", "", "").type);
        assertEquals(VoiceCommand.Type.STOP_LISTENING, VoiceCommand.fromBrain("STOP_LISTENING", "", "").type);
    }

    @Test
    public void brainCannotInventActions() {
        assertNull(VoiceCommand.fromBrain("DELETE_ALL", "x", ""));
        assertNull(VoiceCommand.fromBrain("UNKNOWN", "", ""));
        assertNull(VoiceCommand.fromBrain(null, "", ""));
        assertNull(VoiceCommand.fromBrain("ALARM", "25:00", ""));
        assertNull(VoiceCommand.fromBrain("ALARM", "seven", ""));
        assertNull(VoiceCommand.fromBrain("TIMER", "0", ""));
        assertNull(VoiceCommand.fromBrain("TIMER", "999999", ""));
        assertNull(VoiceCommand.fromBrain("REMINDER", "+60", ""));
        assertNull(VoiceCommand.fromBrain("VOLUME", "loud", ""));
        assertNull(VoiceCommand.fromBrain("SETTINGS", "developer options", ""));
        assertNull(VoiceCommand.fromBrain("PLAY_ITEM", "9", ""));
        assertNull(VoiceCommand.fromBrain("OPEN_APP", "", ""));
        assertNull(VoiceCommand.fromBrain("SEARCH", "youtube", ""));
        assertNull(VoiceCommand.fromBrain("CALL_CONTROL", "hold", ""));
    }

    @Test
    public void parsesRepeatScreenshotLock() {
        assertEquals(VoiceCommand.Type.REPEAT, VoiceCommand.parse("repeat that").type);
        assertEquals(VoiceCommand.Type.REPEAT, VoiceCommand.parse("what did you say").type);
        assertEquals(VoiceCommand.Type.SCREENSHOT, VoiceCommand.parse("take a screenshot").type);
        assertEquals(VoiceCommand.Type.SCREENSHOT, VoiceCommand.parse("screenshot").type);
        assertEquals(VoiceCommand.Type.LOCK, VoiceCommand.parse("lock the phone").type);
        assertEquals(VoiceCommand.Type.LOCK, VoiceCommand.parse("please lock my phone").type);
        assertEquals(VoiceCommand.Type.PHOTO, VoiceCommand.parse("take a photo").type);
        assertEquals(VoiceCommand.Type.STOP_LISTENING, VoiceCommand.parse("stop listening").type);
        assertEquals(VoiceCommand.Type.LOCK, VoiceCommand.fromBrain("lock", "", "").type);
    }

    @Test
    public void privateActionsNeedUnlock() {
        assertTrue(VoiceCommand.isPrivate(VoiceCommand.Type.READ_NOTIFICATIONS));
        assertTrue(VoiceCommand.isPrivate(VoiceCommand.Type.READ_NOTES));
        assertTrue(VoiceCommand.isPrivate(VoiceCommand.Type.TYPE));
        assertFalse(VoiceCommand.isPrivate(VoiceCommand.Type.TIME));
        assertFalse(VoiceCommand.isPrivate(VoiceCommand.Type.CALL_CONTROL));
    }

    @Test
    public void parsesBatchFive() {
        assertEquals(VoiceCommand.Type.BRIEFING, VoiceCommand.parse("good morning").type);
        assertEquals(VoiceCommand.Type.BRIEFING, VoiceCommand.parse("how is my day").type);
        assertEquals(VoiceCommand.Type.NEXT_ALARM, VoiceCommand.parse("when is my next alarm").type);
        VoiceCommand c = VoiceCommand.parse("what's on my calendar tomorrow");
        assertEquals(VoiceCommand.Type.CALENDAR, c.type);
        assertEquals("tomorrow", c.target);
        assertEquals("today", VoiceCommand.parse("my schedule").target);
        c = VoiceCommand.parse("turn off do not disturb");
        assertEquals(VoiceCommand.Type.DND, c.type);
        assertEquals("off", c.target);
        assertEquals("on", VoiceCommand.parse("dnd on").target);
        assertEquals("on", VoiceCommand.parse("do not disturb").target);
        c = VoiceCommand.parse("put the phone on silent");
        assertEquals(VoiceCommand.Type.RINGER, c.type);
        assertEquals("silent", c.target);
        assertEquals("vibrate", VoiceCommand.parse("vibrate mode").target);
        assertEquals("normal", VoiceCommand.parse("put phone in normal mode").target);
        c = VoiceCommand.parse("what is 15 percent of 2000");
        assertEquals(VoiceCommand.Type.CALCULATE, c.type);
        assertEquals(VoiceCommand.Type.CALCULATE, VoiceCommand.parse("25 times 4").type);
        assertEquals(VoiceCommand.Type.MISSED_CALLS, VoiceCommand.parse("who called me").type);
        assertEquals(VoiceCommand.Type.CALL_BACK, VoiceCommand.parse("call him back").type);
        assertEquals(VoiceCommand.Type.CALL, VoiceCommand.parse("call ravi").type);
        c = VoiceCommand.parse("remember that my wife's name is priya");
        assertEquals(VoiceCommand.Type.REMEMBER, c.type);
        assertEquals("my wife's name is priya", c.message);
        assertEquals(VoiceCommand.Type.REMEMBER, VoiceCommand.parse("remember my car is a red swift").type);
        assertEquals(VoiceCommand.Type.READ_MEMORY, VoiceCommand.parse("what do you know about me").type);
        c = VoiceCommand.parse("forget everything");
        assertEquals(VoiceCommand.Type.FORGET, c.type);
        assertEquals("everything", c.target);
        assertEquals("my car", VoiceCommand.parse("forget about my car").target);
        // Old commands still parse the same way.
        assertEquals(VoiceCommand.Type.REMINDER, VoiceCommand.parse("remind me to drink water at 5 pm").type);
        assertEquals(VoiceCommand.Type.TIME, VoiceCommand.parse("what time is it").type);
        assertEquals(VoiceCommand.Type.WEATHER, VoiceCommand.parse("what is the weather").type);
        assertEquals(VoiceCommand.Type.TIMER, VoiceCommand.parse("timer 5 minutes").type);
        assertEquals(VoiceCommand.Type.VOLUME, VoiceCommand.parse("volume up").type);
        assertEquals(VoiceCommand.Type.CALCULATE, VoiceCommand.fromBrain("CALCULATE", "", "2000*15/100").type);
        assertNull(VoiceCommand.fromBrain("CALCULATE", "", "rm -rf"));
        assertEquals("silent", VoiceCommand.fromBrain("RINGER", "silent", "").target);
        assertTrue(VoiceCommand.isPrivate(VoiceCommand.Type.READ_MEMORY));
        assertFalse(VoiceCommand.isPrivate(VoiceCommand.Type.RINGER));
    }
}
