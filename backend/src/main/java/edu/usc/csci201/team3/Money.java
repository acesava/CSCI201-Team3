package edu.usc.csci201.team3;

import java.math.BigDecimal;

final class Money {
    private Money() {}

    static BigDecimal dollars(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    static String format(long cents) {
        return (cents < 0 ? "-$" : "$") + dollars(Math.abs(cents)).toPlainString();
    }
}
