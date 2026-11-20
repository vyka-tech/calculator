package com.codeshield.calculator;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Arbitrary-precision calculator. All arithmetic uses BigDecimal with a bounded MathContext, and inputs are
 * validated (length, format, exponent size) so a request cannot trigger huge computations.
 */
final class Calculator {

    /** Plain decimal numbers only: optional sign, up to 30 integer/fraction digits, optional small exponent. */
    private static final Pattern NUMBER = Pattern.compile("^[+-]?\\d{1,30}(\\.\\d{1,30})?([eE][+-]?\\d{1,3})?$");
    private static final int MAX_INPUT_LENGTH = 70;

    /** Operation name -> display symbol */
    static final Map<String, String> SYMBOLS = ImmutableMap.<String, String>builder()   // guava
            .put("add", "+")
            .put("subtract", "\u2212")
            .put("multiply", "\u00d7")
            .put("divide", "\u00f7")
            .put("power", "^")
            .put("modulo", "mod")
            .build();

    private final MathContext mathContext;
    private final int maxExponent;
    private final List<String> enabledOps;

    Calculator(int precision, int maxExponent, List<String> enabledOps) {
        this.mathContext = new MathContext(Math.max(1, Math.min(precision, 50)), RoundingMode.HALF_EVEN);
        this.maxExponent = Math.max(0, Math.min(maxExponent, 1000));
        this.enabledOps  = enabledOps.stream().filter(SYMBOLS::containsKey).toList();
    }

    List<String> operations() {
        return enabledOps;
    }

    /** Thrown for invalid input; the message is safe to show to the caller. */
    static final class CalcException extends Exception {
        CalcException(String message) {
            super(message);
        }
    }

    Map<String, Object> calculate(String rawA, String rawB, String rawOp) throws CalcException {
        String op = StringUtils.trimToEmpty(rawOp).toLowerCase();                       // commons-lang3
        if (!enabledOps.contains(op)) {
            throw new CalcException("Unknown operation. Use one of: " + Joiner.on(", ").join(enabledOps));   // guava
        }
        BigDecimal a = parse("a", rawA);
        BigDecimal b = parse("b", rawB);

        BigDecimal result;
        switch (op) {
            case "add":      result = a.add(b, mathContext); break;
            case "subtract": result = a.subtract(b, mathContext); break;
            case "multiply": result = a.multiply(b, mathContext); break;
            case "divide":
                requireNonZero(b);
                result = a.divide(b, mathContext);
                break;
            case "modulo":
                requireNonZero(b);
                result = a.remainder(b, mathContext);
                break;
            case "power":
                result = a.pow(exponent(b), mathContext);
                break;
            default:
                throw new CalcException("Unsupported operation");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("a", format(a));
        out.put("b", format(b));
        out.put("op", op);
        out.put("expression", format(a) + " " + SYMBOLS.get(op) + " " + format(b));
        out.put("result", format(result));
        return out;
    }

    private BigDecimal parse(String label, String raw) throws CalcException {
        String s = StringUtils.trimToEmpty(raw);                                           // commons-lang3
        if (StringUtils.isEmpty(s)) {
            throw new CalcException("Missing number '" + label + "'");
        }
        if (s.length() > MAX_INPUT_LENGTH || !NUMBER.matcher(s).matches() || !NumberUtils.isCreatable(s)) {
            throw new CalcException("'" + label + "' must be a plain decimal number, e.g. 12.5 or -3");
        }
        return new BigDecimal(s);
    }

    private static void requireNonZero(BigDecimal b) throws CalcException {
        if (b.signum() == 0) {
            throw new CalcException("Cannot divide by zero");
        }
    }

    private int exponent(BigDecimal b) throws CalcException {
        try {
            int n = b.intValueExact();
            if (n < 0 || n > maxExponent) {
                throw new CalcException("Exponent must be a whole number between 0 and " + maxExponent);
            }
            return n;
        } catch (ArithmeticException e) {
            throw new CalcException("Exponent must be a whole number between 0 and " + maxExponent);
        }
    }

    /** Plain notation for ordinary numbers, scientific for very large/small ones. */
    static String format(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        int magnitude = s.precision() - s.scale();
        if (s.signum() == 0) {
            return "0";
        }
        return (magnitude > 30 || magnitude < -10) ? s.toString() : s.toPlainString();
    }
}
