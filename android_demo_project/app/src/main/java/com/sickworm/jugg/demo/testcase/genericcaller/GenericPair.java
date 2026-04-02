package com.sickworm.jugg.demo.testcase.genericcaller;

public class GenericPair {

    private final int left;
    private final String right;

    public GenericPair(int left, String right) {
        this.left = left;
        this.right = right;
    }

    public int getLeft() {
        return left;
    }

    public String getRight() {
        return right;
    }
}
