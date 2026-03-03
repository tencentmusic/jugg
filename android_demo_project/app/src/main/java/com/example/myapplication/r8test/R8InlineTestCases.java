package com.example.myapplication.r8test;

/**
 * R8 内联优化测试用例
 * 用于验证 R8 是否会内联有副作用的方法
 */
public class R8InlineTestCases {

    // ========== 测试用例 1: 访问私有字段的实例方法 ==========

    public static class Counter {
        private int count = 0;

        public void increment() {
            count++;  // 访问并修改私有字段
        }

        public int getCount() {
            return count;  // 访问私有字段
        }
    }

    public static class CounterCaller {
        public void testIncrement() {
            Counter c = new Counter();
            c.increment();  // 会被 R8 内联吗？
            System.out.println(c.getCount());
        }
    }

    // ========== 测试用例 2: Getter 方法访问私有字段 ==========

    public static class Person {
        private String name;
        private int age;

        public Person(String name, int age) {
            this.name = name;
            this.age = age;
        }

        public String getName() {
            return name;  // 访问私有字段
        }

        public int getAge() {
            return age;  // 访问私有字段
        }
    }

    public static class PersonCaller {
        public void testGetter() {
            Person p = new Person("Alice", 25);
            String name = p.getName();  // 会被 R8 内联吗？
            int age = p.getAge();  // 会被 R8 内联吗？
            System.out.println(name + " " + age);
        }
    }

    // ========== 测试用例 3: Setter 方法修改私有字段 ==========

    public static class Config {
        private boolean enabled = false;

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;  // 修改私有字段
        }

        public boolean isEnabled() {
            return enabled;
        }
    }

    public static class ConfigCaller {
        public void testSetter() {
            Config config = new Config();
            config.setEnabled(true);  // 会被 R8 内联吗？
            System.out.println(config.isEnabled());
        }
    }

    // ========== 测试用例 4: 单例模式 ==========

    public static class Singleton {
        private static Singleton instance;
        private int value = 0;

        private Singleton() {}

        public static Singleton getInstance() {
            if (instance == null) {
                instance = new Singleton();
            }
            return instance;
        }

        public void setValue(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public static class SingletonCaller {
        public void testSingleton() {
            Singleton s = Singleton.getInstance();  // 会被 R8 内联吗？
            s.setValue(42);
            System.out.println(s.getValue());
        }
    }

    // ========== 测试用例 5: 静态纯函数（无副作用）==========

    public static class MathUtils {
        public static int add(int a, int b) {
            return a + b;  // 纯函数，无状态
        }

        public static int multiply(int a, int b) {
            return a * b;  // 纯函数，无状态
        }
    }

    public static class MathUtilsCaller {
        public void testPureFunction() {
            int result1 = MathUtils.add(1, 2);  // 会被 R8 内联吗？
            int result2 = MathUtils.multiply(3, 4);  // 会被 R8 内联吗？
            System.out.println(result1 + result2);
        }
    }

    // ========== 测试用例 6: 静态方法访问静态字段 ==========

    public static class GlobalConfig {
        private static int globalValue = 100;

        public static int getGlobalValue() {
            return globalValue;  // 访问静态字段
        }

        public static void setGlobalValue(int value) {
            globalValue = value;  // 修改静态字段
        }
    }

    public static class GlobalConfigCaller {
        public void testStaticField() {
            int value = GlobalConfig.getGlobalValue();  // 会被 R8 内联吗？
            GlobalConfig.setGlobalValue(200);  // 会被 R8 内联吗？
            System.out.println(value);
        }
    }

    // ========== 测试用例 7: 链式调用 ==========

    public static class Builder {
        private String name;
        private int age;

        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        public Builder setAge(int age) {
            this.age = age;
            return this;
        }

        public String build() {
            return name + ":" + age;
        }
    }

    public static class BuilderCaller {
        public void testBuilder() {
            String result = new Builder()
                    .setName("Bob")  // 会被 R8 内联吗？
                    .setAge(30)      // 会被 R8 内联吗？
                    .build();        // 会被 R8 内联吗？
            System.out.println(result);
        }
    }

    // ========== 测试用例 8: 小的辅助方法 ==========

    public static class StringHelper {
        private String prefix = "[LOG] ";

        public String formatMessage(String msg) {
            return prefix + msg;  // 访问私有字段
        }

        private String internalFormat(String msg) {
            return "[" + msg + "]";  // 私有方法
        }

        public String formatWithBrackets(String msg) {
            return internalFormat(msg);  // 调用私有方法
        }
    }

    public static class StringHelperCaller {
        public void testHelper() {
            StringHelper helper = new StringHelper();
            String msg1 = helper.formatMessage("Hello");  // 会被 R8 内联吗？
            String msg2 = helper.formatWithBrackets("World");  // 会被 R8 内联吗？
            System.out.println(msg1 + msg2);
        }
    }

    // ========== 测试用例 9: 只被调用一次的方法 ==========

    public static class OnceCalledMethod {
        private int data = 0;

        private void helperMethod() {
            data = data * 2 + 1;  // 只被一个地方调用
        }

        public void publicMethod() {
            helperMethod();  // 唯一调用点
            System.out.println(data);
        }
    }

    public static class OnceCalledMethodCaller {
        public void testOnceCalled() {
            OnceCalledMethod obj = new OnceCalledMethod();
            obj.publicMethod();
        }
    }

    // ========== 测试用例 10: 构造函数 ==========

    public static class Point {
        private int x;
        private int y;

        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }
    }

    public static class PointCaller {
        public void testConstructor() {
            Point p = new Point(10, 20);  // 构造函数会被��联吗？
            System.out.println(p.getX() + p.getY());
        }
    }

    // ========== 主入口：调用所有测试用例 ==========

    public static void runAllTests() {
        new CounterCaller().testIncrement();
        new PersonCaller().testGetter();
        new ConfigCaller().testSetter();
        new SingletonCaller().testSingleton();
        new MathUtilsCaller().testPureFunction();
        new GlobalConfigCaller().testStaticField();
        new BuilderCaller().testBuilder();
        new StringHelperCaller().testHelper();
        new OnceCalledMethodCaller().testOnceCalled();
        new PointCaller().testConstructor();
    }
}
