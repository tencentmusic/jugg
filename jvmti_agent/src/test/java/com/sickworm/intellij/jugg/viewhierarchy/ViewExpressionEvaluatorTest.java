package com.sickworm.intellij.jugg.viewhierarchy;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * ViewExpressionEvaluatorTest covers expression parsing, security validation,
 * chain evaluation, and value serialization.
 */
public class ViewExpressionEvaluatorTest {

    // ---- Parsing tests ----

    @Test
    public void parseChain_simpleNoArgMethod() throws Exception {
        List<ViewExpressionEvaluator.MethodCall> chain =
            ViewExpressionEvaluator.parseChain("getText()");
        Assert.assertEquals(1, chain.size());
        Assert.assertEquals("getText", chain.get(0).methodName);
        Assert.assertEquals(0, chain.get(0).args.length);
    }

    @Test
    public void parseChain_methodChain() throws Exception {
        List<ViewExpressionEvaluator.MethodCall> chain =
            ViewExpressionEvaluator.parseChain("getText().toString()");
        Assert.assertEquals(2, chain.size());
        Assert.assertEquals("getText", chain.get(0).methodName);
        Assert.assertEquals("toString", chain.get(1).methodName);
    }

    @Test
    public void parseChain_threeDeepChain() throws Exception {
        List<ViewExpressionEvaluator.MethodCall> chain =
            ViewExpressionEvaluator.parseChain("getBackground().getClass().getSimpleName()");
        Assert.assertEquals(3, chain.size());
        Assert.assertEquals("getBackground", chain.get(0).methodName);
        Assert.assertEquals("getClass", chain.get(1).methodName);
        Assert.assertEquals("getSimpleName", chain.get(2).methodName);
    }

    @Test
    public void parseChain_intArgument() throws Exception {
        List<ViewExpressionEvaluator.MethodCall> chain =
            ViewExpressionEvaluator.parseChain("getChildAt(0)");
        Assert.assertEquals(1, chain.size());
        Assert.assertEquals("getChildAt", chain.get(0).methodName);
        Assert.assertEquals(1, chain.get(0).args.length);
        Assert.assertEquals(0, chain.get(0).args[0]);
    }

    @Test
    public void parseChain_stringArgument() throws Exception {
        List<ViewExpressionEvaluator.MethodCall> chain =
            ViewExpressionEvaluator.parseChain("getTag(\"key\")");
        Assert.assertEquals(1, chain.size());
        Assert.assertEquals("getTag", chain.get(0).methodName);
        Assert.assertEquals(1, chain.get(0).args.length);
        Assert.assertEquals("key", chain.get(0).args[0]);
    }

    @Test(expected = ViewExpressionEvaluator.EvalException.class)
    public void parseChain_emptyExpressionThrows() throws Exception {
        ViewExpressionEvaluator.parseChain("");
    }

    @Test(expected = ViewExpressionEvaluator.EvalException.class)
    public void parseChain_missingParensThrows() throws Exception {
        ViewExpressionEvaluator.parseChain("getText");
    }

    @Test(expected = ViewExpressionEvaluator.EvalException.class)
    public void parseChain_untermStringLiteralThrows() throws Exception {
        ViewExpressionEvaluator.parseChain("getTag(\"unterminated)");
    }

    // ---- Security validation tests ----

    @Test
    public void validateMethodName_getterAllowed() throws Exception {
        ViewExpressionEvaluator.validateMethodName("getText");
        ViewExpressionEvaluator.validateMethodName("isEnabled");
        ViewExpressionEvaluator.validateMethodName("hasSelection");
        ViewExpressionEvaluator.validateMethodName("canScrollVertically");
        ViewExpressionEvaluator.validateMethodName("shouldShowSelector");
    }

    @Test
    public void validateMethodName_allowlistAllowed() throws Exception {
        ViewExpressionEvaluator.validateMethodName("toString");
        ViewExpressionEvaluator.validateMethodName("length");
        ViewExpressionEvaluator.validateMethodName("name");
        ViewExpressionEvaluator.validateMethodName("ordinal");
        ViewExpressionEvaluator.validateMethodName("size");
        ViewExpressionEvaluator.validateMethodName("isEmpty");
    }

    @Test(expected = ViewExpressionEvaluator.EvalException.class)
    public void validateMethodName_setterBlocked() throws Exception {
        ViewExpressionEvaluator.validateMethodName("setText");
    }

    @Test(expected = ViewExpressionEvaluator.EvalException.class)
    public void validateMethodName_removeBlocked() throws Exception {
        ViewExpressionEvaluator.validateMethodName("removeView");
    }

    @Test(expected = ViewExpressionEvaluator.EvalException.class)
    public void validateMethodName_postBlocked() throws Exception {
        ViewExpressionEvaluator.validateMethodName("postDelayed");
    }

    @Test(expected = ViewExpressionEvaluator.EvalException.class)
    public void validateMethodName_performBlocked() throws Exception {
        ViewExpressionEvaluator.validateMethodName("performClick");
    }

    @Test(expected = ViewExpressionEvaluator.EvalException.class)
    public void validateMethodName_dispatchBlocked() throws Exception {
        ViewExpressionEvaluator.validateMethodName("dispatchTouchEvent");
    }

    @Test(expected = ViewExpressionEvaluator.EvalException.class)
    public void validateMethodName_executeBlocked() throws Exception {
        ViewExpressionEvaluator.validateMethodName("executeKeyEvent");
    }

    @Test(expected = ViewExpressionEvaluator.EvalException.class)
    public void validateMethodName_unknownMethodBlocked() throws Exception {
        ViewExpressionEvaluator.validateMethodName("fooBar");
    }

    // ---- Chain depth limit test ----

    @Test(expected = ViewExpressionEvaluator.EvalException.class)
    public void evaluate_exceedMaxChainDepthThrows() throws Exception {
        ViewExpressionEvaluator.evaluate(
            "hello",
            "getClass().getSimpleName().toString().length().intValue().hashCode()"
        );
    }

    // ---- Evaluation tests (using plain Java objects) ----

    @Test
    public void evaluate_simpleGetter() throws Exception {
        SampleBean bean = new SampleBean("hello", 42, true);
        ViewExpressionEvaluator.Result result =
            ViewExpressionEvaluator.evaluate(bean, "getText()");
        Assert.assertEquals("hello", result.jsonValue);
        Assert.assertEquals("string", result.typeName);
    }

    @Test
    public void evaluate_intGetter() throws Exception {
        SampleBean bean = new SampleBean("hello", 42, true);
        ViewExpressionEvaluator.Result result =
            ViewExpressionEvaluator.evaluate(bean, "getCount()");
        Assert.assertEquals(42, result.jsonValue);
        Assert.assertEquals("int", result.typeName);
    }

    @Test
    public void evaluate_booleanGetter() throws Exception {
        SampleBean bean = new SampleBean("hello", 42, true);
        ViewExpressionEvaluator.Result result =
            ViewExpressionEvaluator.evaluate(bean, "isEnabled()");
        Assert.assertEquals(true, result.jsonValue);
        Assert.assertEquals("boolean", result.typeName);
    }

    @Test
    public void evaluate_methodChain() throws Exception {
        SampleBean bean = new SampleBean("hello", 42, true);
        ViewExpressionEvaluator.Result result =
            ViewExpressionEvaluator.evaluate(bean, "getText().length()");
        Assert.assertEquals(5, result.jsonValue);
        Assert.assertEquals("int", result.typeName);
    }

    @Test
    public void evaluate_chainToClassName() throws Exception {
        SampleBean bean = new SampleBean("hello", 42, true);
        ViewExpressionEvaluator.Result result =
            ViewExpressionEvaluator.evaluate(bean, "getClass().getSimpleName()");
        Assert.assertEquals("SampleBean", result.jsonValue);
        Assert.assertEquals("string", result.typeName);
    }

    @Test
    public void evaluate_nullSafe() throws Exception {
        SampleBean bean = new SampleBean(null, 0, false);
        ViewExpressionEvaluator.Result result =
            ViewExpressionEvaluator.evaluate(bean, "getText()");
        Assert.assertNull(result.jsonValue);
        Assert.assertEquals("null", result.typeName);
    }

    @Test
    public void evaluate_nullChainReturnNullType() throws Exception {
        SampleBean bean = new SampleBean(null, 0, false);
        ViewExpressionEvaluator.Result result =
            ViewExpressionEvaluator.evaluate(bean, "getText().length()");
        Assert.assertNull(result.jsonValue);
        Assert.assertEquals("null", result.typeName);
    }

    @Test
    public void evaluate_noSuchMethodReturnsError() {
        SampleBean bean = new SampleBean("hello", 42, true);
        try {
            ViewExpressionEvaluator.evaluate(bean, "getInvalid()");
            Assert.fail("Expected EvalException");
        } catch (ViewExpressionEvaluator.EvalException e) {
            Assert.assertTrue(e.getMessage().contains("NoSuchMethodException"));
            Assert.assertTrue(e.getMessage().contains("getInvalid"));
        }
    }

    @Test
    public void evaluate_floatGetter() throws Exception {
        SampleBean bean = new SampleBean("hello", 42, true);
        ViewExpressionEvaluator.Result result =
            ViewExpressionEvaluator.evaluate(bean, "getAlpha()");
        Assert.assertEquals("float", result.typeName);
        Assert.assertTrue(result.jsonValue instanceof Number);
        Assert.assertEquals(1.0, ((Number) result.jsonValue).doubleValue(), 0.001);
    }

    @Test
    public void evaluate_enumGetter() throws Exception {
        SampleBean bean = new SampleBean("hello", 42, true);
        ViewExpressionEvaluator.Result result =
            ViewExpressionEvaluator.evaluate(bean, "getStatus()");
        Assert.assertEquals("ACTIVE", result.jsonValue);
        Assert.assertEquals("string", result.typeName);
    }

    @Test
    public void evaluate_enumNameChain() throws Exception {
        SampleBean bean = new SampleBean("hello", 42, true);
        ViewExpressionEvaluator.Result result =
            ViewExpressionEvaluator.evaluate(bean, "getStatus().name()");
        Assert.assertEquals("ACTIVE", result.jsonValue);
        Assert.assertEquals("string", result.typeName);
    }

    // ---- Serialization tests ----

    @Test
    public void serializeValue_int() {
        ViewExpressionEvaluator.Result r = ViewExpressionEvaluator.serializeValue(42);
        Assert.assertEquals(42, r.jsonValue);
        Assert.assertEquals("int", r.typeName);
    }

    @Test
    public void serializeValue_long() {
        ViewExpressionEvaluator.Result r = ViewExpressionEvaluator.serializeValue(42L);
        Assert.assertEquals(42L, r.jsonValue);
        Assert.assertEquals("long", r.typeName);
    }

    @Test
    public void serializeValue_float() {
        ViewExpressionEvaluator.Result r = ViewExpressionEvaluator.serializeValue(3.14f);
        Assert.assertEquals("float", r.typeName);
        Assert.assertEquals(3.14, ((Number) r.jsonValue).doubleValue(), 0.001);
    }

    @Test
    public void serializeValue_double() {
        ViewExpressionEvaluator.Result r = ViewExpressionEvaluator.serializeValue(3.141592653);
        Assert.assertEquals("double", r.typeName);
        Assert.assertEquals(3.141593, ((Number) r.jsonValue).doubleValue(), 0.000001);
    }

    @Test
    public void serializeValue_boolean() {
        ViewExpressionEvaluator.Result r = ViewExpressionEvaluator.serializeValue(true);
        Assert.assertEquals(true, r.jsonValue);
        Assert.assertEquals("boolean", r.typeName);
    }

    @Test
    public void serializeValue_null() {
        ViewExpressionEvaluator.Result r = ViewExpressionEvaluator.serializeValue(null);
        Assert.assertNull(r.jsonValue);
        Assert.assertEquals("null", r.typeName);
    }

    @Test
    public void serializeValue_stringTruncation() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append('x');
        }
        ViewExpressionEvaluator.Result r = ViewExpressionEvaluator.serializeValue(sb.toString());
        Assert.assertEquals("string", r.typeName);
        Assert.assertEquals(500, ((String) r.jsonValue).length());
    }

    // ---- Test support classes ----

    public enum Status { ACTIVE, INACTIVE }

    @SuppressWarnings("unused")
    public static class SampleBean {
        private final String text;
        private final int count;
        private final boolean enabled;

        public SampleBean(String text, int count, boolean enabled) {
            this.text = text;
            this.count = count;
            this.enabled = enabled;
        }

        public String getText() { return text; }
        public int getCount() { return count; }
        public boolean isEnabled() { return enabled; }
        public float getAlpha() { return 1.0f; }
        public Status getStatus() { return Status.ACTIVE; }
    }
}
