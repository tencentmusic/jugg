package com.sickworm.intellij.aidp.test;

public class JavaFileWithInterdependence {

    public static String MESSAGE = "NewDep Hi Aidp!";

    public static void main(String[] args) {
        System.out.println(new NewDep().getMessage());
    }
}
