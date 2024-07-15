package com.sickworm.jugg.demo.testcase.defaultinterface

class ClassWithDefaultParam {
    fun fun1(a: Int, b: Int = 2) {
        println("a=$a, b=$b")
    }
}