package com.intellij.ide.util;

import java.util.HashMap;
import java.util.Map;

public class PropertiesComponent {

    private PropertiesComponent() {
    }

    private static final PropertiesComponent instance = new PropertiesComponent();

    public static PropertiesComponent getInstance() {
        return instance;
    }


    private final Map<String, String> valueMap = new HashMap<>();

    public String getValue(String key) {
        return getValue(key, null);
    }

    public String getValue(String key, String defaultValue) {
        String result = valueMap.get(key);
        if (result != null) {
            return result;
        }
        return defaultValue;
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String result = valueMap.get(key);
        if (result != null) {
            return Boolean.parseBoolean(result);
        }
        return defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        String result = valueMap.get(key);
        if (result != null) {
            return Integer.parseInt(result);
        }
        return defaultValue;
    }

    public float getFloat(String key, float defaultValue) {
        String result = valueMap.get(key);
        if (result != null) {
            return Float.parseFloat(result);
        }
        return defaultValue;
    }

    public void setValue(String key, String value, String defaultValue) {
        if (value == null || value.equals(defaultValue)) {
            valueMap.remove(key);
        } else {
            valueMap.put(key, value);
        }
    }

    public void setValue(String key, int value, int defaultValue) {
        if (value == defaultValue) {
            valueMap.remove(key);
        } else {
            valueMap.put(key, String.valueOf(value));
        }
    }

    public void setValue(String key, float value, float defaultValue) {
        if (value == defaultValue) {
            valueMap.remove(key);
        } else {
            valueMap.put(key, String.valueOf(value));
        }
    }

    public void setValue(String key, boolean value, boolean defaultValue) {
        if (value == defaultValue) {
            valueMap.remove(key);
        } else {
            valueMap.put(key, String.valueOf(value));
        }
    }
}
