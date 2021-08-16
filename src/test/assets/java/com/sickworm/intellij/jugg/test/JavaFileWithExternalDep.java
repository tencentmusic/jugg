package com.sickworm.intellij.jugg.test;

import java.io.File;
import io.reactivex.rxjava3.core.Flowable;

public class JavaFileWithExternalDep {
    public static void main(String[] args) {
        Flowable.just("Hello Jugg!").subscribe(System.out::println);
    }
}
