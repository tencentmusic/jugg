package com.sickworm.intellij.jugg.viewhierarchy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ViewExpressionEvaluator parses dot-separated read-only expressions and evaluates them
 * reflectively on a root object (typically a View). Explicit method calls must be
 * getter/query names; names without "()" may also resolve to public fields.
 * Methods with side effects are blocked.
 *
 * Supported syntax:
 *   expression := access ("." access)*
 *   access := method_call | name
 *   method_call := method_name "()" | method_name "(" literal ")"
 *   literal := integer | float | string_literal
 *
 * A name without "()" is resolved as a public field first, then as a no-arg getter
 * (getName / Kotlin property getXxx / isXxx).
 */
public class ViewExpressionEvaluator {

    static final int MAX_CHAIN_DEPTH = 5;
    private static final int MAX_STRING_LENGTH = 500;

    private static final Set<String> BLOCKED_PREFIXES = new HashSet<>(Arrays.asList(
        "set", "remove", "add", "clear", "delete", "put", "write",
        "post", "send", "dispatch", "perform", "request", "invoke",
        "execute", "notify", "register", "unregister", "attach", "detach"
    ));

    private static final Set<String> ALLOWED_PREFIXES = new HashSet<>(Arrays.asList(
        "get", "is", "has", "can", "should"
    ));

    private static final Set<String> ALLOWLIST = new HashSet<>(Arrays.asList(
        "toString", "length", "name", "ordinal", "size", "isEmpty",
        "hashCode", "intValue", "floatValue", "longValue", "doubleValue"
    ));

    /**
     * Evaluate an expression chain on the given root object.
     * Returns a Result containing the JSON-serializable value and type name.
     */
    public static Result evaluate(Object root, String expression) throws EvalException {
        if (expression == null || expression.trim().isEmpty()) {
            throw new EvalException("expression is empty");
        }

        List<MethodCall> chain = parseChain(expression.trim());
        if (chain.isEmpty()) {
            throw new EvalException("expression parsed to empty chain: " + expression);
        }
        if (chain.size() > MAX_CHAIN_DEPTH) {
            throw new EvalException("chain depth " + chain.size()
                + " exceeds maximum " + MAX_CHAIN_DEPTH);
        }

        Object current = root;
        for (MethodCall call : chain) {
            if (current == null) {
                return new Result(null, "null");
            }
            if (call.bareAccess) {
                current = resolveBareAccess(current, call.methodName);
                continue;
            }
            validateMethodName(call.methodName);
            Method method = findMethod(current.getClass(), call.methodName, call.argTypes);
            if (method == null) {
                throw new EvalException("NoSuchMethodException: "
                    + call.methodName + "(" + describeArgTypes(call.argTypes) + ")"
                    + " on " + current.getClass().getName());
            }
            method.setAccessible(true);
            try {
                current = method.invoke(current, call.args);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new EvalException(cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
            }
        }

        return serializeValue(current);
    }

    // ---- Expression parsing ----

    static List<MethodCall> parseChain(String expression) throws EvalException {
        if (expression == null || expression.trim().isEmpty()) {
            throw new EvalException("expression is empty");
        }
        List<MethodCall> result = new ArrayList<>();
        int pos = 0;
        int len = expression.length();

        while (pos < len) {
            if (pos > 0) {
                if (expression.charAt(pos) != '.') {
                    throw new EvalException("expected '.' at position " + pos
                        + " in: " + expression);
                }
                pos++;
            }

            // Parse method name
            int nameStart = pos;
            while (pos < len && isIdentifierChar(expression.charAt(pos))) {
                pos++;
            }
            if (pos == nameStart) {
                throw new EvalException("expected method name at position " + pos
                    + " in: " + expression);
            }
            String methodName = expression.substring(nameStart, pos);

            if (pos >= len || expression.charAt(pos) != '(') {
                result.add(new MethodCall(methodName, new Object[0], new Class[0], true));
                continue;
            }
            pos++;

            // Parse arguments
            Object[] args;
            Class<?>[] argTypes;
            if (pos < len && expression.charAt(pos) == ')') {
                args = new Object[0];
                argTypes = new Class[0];
                pos++;
            } else {
                // Parse single literal argument
                ParsedLiteral literal = parseLiteral(expression, pos);
                pos = literal.endPos;
                if (pos >= len || expression.charAt(pos) != ')') {
                    throw new EvalException("expected ')' at position " + pos
                        + " in: " + expression);
                }
                pos++;
                args = new Object[]{literal.value};
                argTypes = new Class[]{literal.type};
            }

            result.add(new MethodCall(methodName, args, argTypes, false));
        }

        return result;
    }

    private static ParsedLiteral parseLiteral(String expr, int pos) throws EvalException {
        int len = expr.length();
        if (pos >= len) {
            throw new EvalException("unexpected end of expression while parsing literal");
        }

        char ch = expr.charAt(pos);

        // String literal
        if (ch == '"') {
            int start = pos + 1;
            int end = expr.indexOf('"', start);
            if (end < 0) {
                throw new EvalException("unterminated string literal at position " + pos);
            }
            String value = expr.substring(start, end);
            return new ParsedLiteral(value, String.class, end + 1);
        }

        // Numeric literal
        int numStart = pos;
        boolean hasDecimalPoint = false;
        if (ch == '-') {
            pos++;
        }
        while (pos < len) {
            char c = expr.charAt(pos);
            if (c == '.' && !hasDecimalPoint) {
                hasDecimalPoint = true;
                pos++;
            } else if (Character.isDigit(c)) {
                pos++;
            } else {
                break;
            }
        }
        if (pos == numStart || (pos == numStart + 1 && expr.charAt(numStart) == '-')) {
            throw new EvalException("expected numeric literal at position " + numStart);
        }

        String numStr = expr.substring(numStart, pos);
        if (hasDecimalPoint) {
            return new ParsedLiteral(Float.parseFloat(numStr), float.class, pos);
        } else {
            return new ParsedLiteral(Integer.parseInt(numStr), int.class, pos);
        }
    }

    // ---- Security validation ----

    static void validateMethodName(String name) throws EvalException {
        validateBlockedPrefix(name);
        if (hasAllowedPrefix(name) || ALLOWLIST.contains(name)) {
            return;
        }
        throw new EvalException("method '" + name
            + "' is not in the getter allowlist");
    }

    static void validateBlockedPrefix(String name) throws EvalException {
        for (String prefix : BLOCKED_PREFIXES) {
            if (name.startsWith(prefix)) {
                throw new EvalException("method '" + name
                    + "' is blocked (potential side-effect)");
            }
        }
    }

    private static Object resolveBareAccess(Object current, String name) throws EvalException {
        validateBlockedPrefix(name);
        Field field = findPublicField(current.getClass(), name);
        if (field != null) {
            try {
                return field.get(current);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new EvalException(cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
            }
        }
        for (String methodName : getterNamesFor(name)) {
            try {
                validateMethodName(methodName);
            } catch (EvalException ignored) {
                continue;
            }
            Method method = findMethod(current.getClass(), methodName, new Class[0]);
            if (method == null) {
                continue;
            }
            method.setAccessible(true);
            try {
                return method.invoke(current);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new EvalException(cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
            }
        }
        throw new EvalException("NoSuchFieldException/NoSuchMethodException: "
            + name + " on " + current.getClass().getName());
    }

    private static List<String> getterNamesFor(String name) {
        List<String> names = new ArrayList<>();
        if (hasAllowedPrefix(name)) {
            names.add(name);
            return names;
        }
        String capitalized = capitalize(name);
        names.add("get" + capitalized);
        names.add("is" + capitalized);
        return names;
    }

    private static boolean hasAllowedPrefix(String name) {
        for (String prefix : ALLOWED_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String capitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static Field findPublicField(Class<?> clazz, String name) {
        try {
            return clazz.getField(name);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    // ---- Reflection helpers ----

    private static Method findMethod(Class<?> clazz, String name, Class<?>[] argTypes)
            throws EvalException {
        // First try exact parameter match
        try {
            return clazz.getMethod(name, argTypes);
        } catch (NoSuchMethodException ignored) {
        }

        // For primitive types, try widening (int -> long, float -> double, etc.)
        if (argTypes.length == 0) {
            // Try with no-arg across all methods
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    return m;
                }
            }
        } else if (argTypes.length == 1) {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1) {
                    Class<?> paramType = m.getParameterTypes()[0];
                    if (paramType.isAssignableFrom(argTypes[0])
                        || isWideningConversion(argTypes[0], paramType)) {
                        return m;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isWideningConversion(Class<?> from, Class<?> to) {
        if (from == int.class || from == Integer.class) {
            return to == long.class || to == Long.class
                || to == float.class || to == Float.class
                || to == double.class || to == Double.class;
        }
        if (from == float.class || from == Float.class) {
            return to == double.class || to == Double.class;
        }
        return false;
    }

    // ---- Serialization ----

    static Result serializeValue(Object value) {
        if (value == null) {
            return new Result(null, "null");
        }
        if (value instanceof String) {
            return new Result(truncate((String) value), "string");
        }
        if (value instanceof CharSequence) {
            return new Result(truncate(value.toString()), "string");
        }
        if (value instanceof Integer) {
            return new Result(value, "int");
        }
        if (value instanceof Long) {
            return new Result(value, "long");
        }
        if (value instanceof Float) {
            float f = (Float) value;
            return new Result(roundFloat(f, 3), "float");
        }
        if (value instanceof Double) {
            double d = (Double) value;
            return new Result(roundDouble(d, 6), "double");
        }
        if (value instanceof Boolean) {
            return new Result(value, "boolean");
        }
        if (value instanceof Enum) {
            return new Result(((Enum<?>) value).name(), "string");
        }
        // Fallback: toString
        return new Result(truncate(value.toString()), "string");
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > MAX_STRING_LENGTH ? s.substring(0, MAX_STRING_LENGTH) : s;
    }

    private static double roundFloat(float value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private static double roundDouble(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String describeArgTypes(Class<?>[] types) {
        if (types.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(types[i].getSimpleName());
        }
        return sb.toString();
    }

    // ---- Data classes ----

    /**
     * Result of evaluating one expression: the JSON-serializable value and its type name.
     */
    public static class Result {
        public final Object jsonValue;
        public final String typeName;

        public Result(Object jsonValue, String typeName) {
            this.jsonValue = jsonValue;
            this.typeName = typeName;
        }
    }

    /**
     * Exception thrown when expression evaluation fails.
     */
    public static class EvalException extends Exception {
        public EvalException(String message) {
            super(message);
        }
    }

    static class MethodCall {
        final String methodName;
        final Object[] args;
        final Class<?>[] argTypes;
        final boolean bareAccess;

        MethodCall(String methodName, Object[] args, Class<?>[] argTypes, boolean bareAccess) {
            this.methodName = methodName;
            this.args = args;
            this.argTypes = argTypes;
            this.bareAccess = bareAccess;
        }
    }

    private static class ParsedLiteral {
        final Object value;
        final Class<?> type;
        final int endPos;

        ParsedLiteral(Object value, Class<?> type, int endPos) {
            this.value = value;
            this.type = type;
            this.endPos = endPos;
        }
    }
}
