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
            new AppMatcher.App("Camera", "com.motorola.camera3"));

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
}
