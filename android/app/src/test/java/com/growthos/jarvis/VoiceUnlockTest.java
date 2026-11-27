package com.growthos.jarvis;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoiceUnlockTest {
    @Test public void readsSpokenPins() {
        assertEquals("4719", VoiceUnlock.digitsFromSpeech("4719"));
        assertEquals("4719", VoiceUnlock.digitsFromSpeech("4 7 1 9"));
        assertEquals("4719", VoiceUnlock.digitsFromSpeech("four seven one nine"));
        assertEquals("4719", VoiceUnlock.digitsFromSpeech("My PIN is 4 7 1 9."));
        assertEquals("5512", VoiceUnlock.digitsFromSpeech("double five one two"));
        assertEquals("0008", VoiceUnlock.digitsFromSpeech("triple zero eight"));
        assertEquals("2480", VoiceUnlock.digitsFromSpeech("to for ate oh"));
        assertEquals("123456", VoiceUnlock.digitsFromSpeech("12 34 56"));
    }

    @Test public void rejectsThingsThatAreNotPins() {
        assertNull(VoiceUnlock.digitsFromSpeech(""));
        assertNull(VoiceUnlock.digitsFromSpeech(null));
        assertNull(VoiceUnlock.digitsFromSpeech("123"));
        assertNull(VoiceUnlock.digitsFromSpeech("open whatsapp"));
        assertNull(VoiceUnlock.digitsFromSpeech("one two three banana"));
        assertNull(VoiceUnlock.digitsFromSpeech("double"));
        assertNull(VoiceUnlock.digitsFromSpeech("12345678901234567"));
    }

    @Test public void normalizesPasswords() {
        assertEquals("blue mango", VoiceUnlock.normalize("  Blue, Mango! "));
        assertEquals("blue mango", VoiceUnlock.stripPreamble(VoiceUnlock.normalize("My password is blue mango")));
        assertEquals("blue mango", VoiceUnlock.stripPreamble("blue mango"));
    }

    @Test public void hashMatchesOnlyTheSamePassword() throws Exception {
        byte[] salt = new byte[16];
        salt[0] = 7;
        byte[] a = VoiceUnlock.hash("blue mango", salt);
        assertArrayEquals(a, VoiceUnlock.hash("blue mango", salt));
        assertFalse(java.util.Arrays.equals(a, VoiceUnlock.hash("blue mangoes", salt)));
        byte[] other = new byte[16];
        assertFalse(java.util.Arrays.equals(a, VoiceUnlock.hash("blue mango", other)));
    }

    @Test public void parsesUnlockCommand() {
        assertEquals(VoiceCommand.Type.UNLOCK, VoiceCommand.parse("unlock my phone").type);
        assertEquals(VoiceCommand.Type.UNLOCK, VoiceCommand.parse("Hey Jarvis unlock").type);
        assertEquals(VoiceCommand.Type.UNLOCK, VoiceCommand.parse("please unlock the phone").type);
        assertEquals(VoiceCommand.Type.UNLOCK, VoiceCommand.parse("open my phone").type);
        assertEquals(VoiceCommand.Type.LOCK, VoiceCommand.parse("lock my phone").type);
        assertEquals(VoiceCommand.Type.OPEN_APP, VoiceCommand.parse("open phone").type);
    }
}
