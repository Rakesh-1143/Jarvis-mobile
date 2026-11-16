package com.growthos.jarvis;

import static org.junit.Assert.*;
import org.junit.Test;

public class CalcTest {
    private static String f(String s) { Double v = Calc.eval(s); return v == null ? null : Calc.format(v); }

    @Test
    public void spokenMaths() {
        assertEquals("100", f("25 times 4"));
        assertEquals("100", f("25 x 4"));
        assertEquals("300", f("15 percent of 2000"));
        assertEquals("12", f("square root of 144"));
        assertEquals("14.2857", f("100 divided by 7"));
        assertEquals("25", f("5 squared"));
        assertEquals("20", f("(2 + 3) * 4"));
        assertEquals("7", f("12 minus 5"));
        assertEquals("4", f("2 plus 2"));
        assertEquals("5", f("2 point 5 times 2"));
        assertEquals("1024", f("2 to the power of 10"));
        assertEquals("1500", f("1,000 + 500"));
    }

    @Test
    public void notMaths() {
        assertNull(f("the weather"));
        assertNull(f("5"));
        assertNull(f("open youtube"));
        assertNull(f("5 minutes"));
        assertNull(f("10 divided by 0"));
        assertNull(f("call 9876543210"));
        assertNull(f("for"));
    }

    @Test
    public void memoryRefusesSecrets() {
        assertTrue(Memory.secret("my atm pin is 1234"));
        assertTrue(Memory.secret("my password is tiger"));
        assertTrue(Memory.secret("card 4111111111111111"));
        assertTrue(Memory.secret("my upi id is x@ybl"));
        assertFalse(Memory.secret("my wife's name is Priya"));
        assertFalse(Memory.secret("my car is a red swift"));
        assertEquals("My wife is Priya", Memory.tidy("  my   wife is Priya "));
    }
}
