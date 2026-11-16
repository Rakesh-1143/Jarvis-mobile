package com.growthos.jarvis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Offline calculator for spoken maths: "25 times 4", "15 percent of 2000", "square root of 144",
 * "100 divided by 7", "5 squared", "(2 + 3) x 4". Pure Java, unit-tested. Returns null if the words
 * are not plain maths, so normal commands and questions are never taken as sums.
 */
final class Calc {
    private Calc() { }

    /** The spoken result, e.g. "25 times 4 is 100", or null if this isn't maths. */
    static String answer(String spoken) {
        Double v = eval(spoken);
        if (v == null) return null;
        return "Boss, that's " + format(v) + ".";
    }

    static Double eval(String spoken) {
        String e = toExpression(spoken);
        if (e == null) return null;
        try {
            Parser p = new Parser(e);
            double v = p.expr();
            p.skip();
            if (p.i != e.length() || Double.isNaN(v) || Double.isInfinite(v)) return null;
            return v;
        } catch (RuntimeException ex) { return null; }
    }

    /** Spoken words to symbols. Returns null when anything other than numbers and maths words is left. */
    static String toExpression(String spoken) {
        if (spoken == null) return null;
        String t = " " + spoken.toLowerCase(Locale.ROOT).replace(",", "").replace("×", "*").replace("÷", "/").replace("−", "-") + " ";
        t = t.replaceAll("\\s+", " ");
        String[][] words = {
                {" square root of ", " r "}, {" root of ", " r "}, {" squared ", " ^2 "}, {" cubed ", " ^3 "},
                {" to the power of ", " ^ "}, {" to the power ", " ^ "}, {" power ", " ^ "},
                {" percent of ", " %of "}, {" % of ", " %of "}, {"% of ", " %of "},
                {" multiplied by ", " * "}, {" times ", " * "}, {" into ", " * "}, {" x ", " * "},
                {" divided by ", " / "}, {" by ", " / "}, {" over ", " / "},
                {" plus ", " + "}, {" add ", " + "}, {" and ", " + "}, {" minus ", " - "}, {" less ", " - "},
                {" point ", " . "}, {" open bracket ", " ( "}, {" close bracket ", " ) "}};
        for (String[] w : words) t = t.replace(w[0], w[1]);
        t = t.replaceAll("(\\d) \\. (\\d)", "$1.$2").trim();
        if (!t.matches("[0-9.+\\-*/^()r% of\\s]+") || !t.matches(".*\\d.*")) return null;
        // Must contain an operator: a lone number is not a sum.
        if (!t.matches(".*(?:[+*/^r]|%of|\\d\\s*-|\\)\\s*-).*")) return null;
        return t.replaceAll("\\s+", "");
    }

    static String format(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9 && Math.abs(v) < 1e15) return String.valueOf((long) Math.rint(v));
        return new BigDecimal(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** Small recursive-descent parser: + - * / ^, brackets, r (square root), a %of b. */
    private static final class Parser {
        final String s; int i;
        Parser(String s) { this.s = s; }
        void skip() { while (i < s.length() && s.charAt(i) == ' ') i++; }
        boolean eat(String tok) { skip(); if (s.startsWith(tok, i)) { i += tok.length(); return true; } return false; }

        double expr() {
            double v = term();
            while (true) {
                if (eat("+")) v += term();
                else if (eat("-")) v -= term();
                else return v;
            }
        }

        double term() {
            double v = factor();
            while (true) {
                if (eat("*")) v *= factor();
                else if (eat("/")) { double d = factor(); if (d == 0) throw new ArithmeticException(); v /= d; }
                else if (eat("%of")) v = v / 100.0 * factor();
                else return v;
            }
        }

        double factor() {
            double b = unary();
            if (eat("^")) return Math.pow(b, factor());
            return b;
        }

        double unary() {
            if (eat("-")) return -unary();
            if (eat("+")) return unary();
            if (eat("r")) { double v = unary(); if (v < 0) throw new ArithmeticException(); return Math.sqrt(v); }
            if (eat("(")) { double v = expr(); if (!eat(")")) throw new IllegalArgumentException(); return v; }
            skip();
            int st = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (st == i) throw new IllegalArgumentException();
            return Double.parseDouble(s.substring(st, i));
        }
    }
}
