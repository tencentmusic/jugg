package com.sickworm.jugg.demo.testcase.desugar;

import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Deprecated
public class JavaInvoker {

    @Deprecated
    private ConcurrentHashMap<String, String> field;

    @Deprecated
    public ConcurrentHashMap<String, String> test(ConcurrentHashMap<String, String> arg) {
        ConcurrentHashMap<String, String> result =  new ConcurrentHashMap<>();
        result.putAll(arg);
        result.put("hello", "world");
        result.elements();
        if (VERSION.SDK_INT >= VERSION_CODES.N) {
            result.entrySet().forEach(o -> {
                //noinspection Convert2MethodRef
                System.out.println("test " + o.getKey() + " -> " + o.getValue());
            });
        }

        if (VERSION.SDK_INT >= VERSION_CODES.N) {
            Optional<String> optional = Optional.of("hello");
        }
        if (VERSION.SDK_INT >= VERSION_CODES.O) {
            Base64.getEncoder().encodeToString("hello".getBytes());
        }

        Optional[] results = {
            Optional.empty(),
            Optional.of("hello"),
        };

        return result;
    }

    public ConcurrentHashMap<ConcurrentHashMap<String, ConcurrentHashMap<String, String>>, ConcurrentHashMap<ConcurrentHashMap<String, String>[], String>> test2(
            ConcurrentHashMap<ConcurrentHashMap<String, ConcurrentHashMap<String, String>>, ConcurrentHashMap<ConcurrentHashMap<String, String>[], String>> arg) {
        return arg;
    }
}
