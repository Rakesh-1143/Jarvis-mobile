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
        assertEquals(VoiceCommand.Type.UNKNOWN, VoiceCommand.parse("what is the weather").type);
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
}
