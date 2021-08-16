package com.sickworm.intellij.jugg.test;

public class JavaFileWithInterdependence {

    public static String MESSAGE = "NewDep Hi Jugg!";

    public static void main(String[] args) {
        System.out.println(new NewDep().getMessage());
    }
}
