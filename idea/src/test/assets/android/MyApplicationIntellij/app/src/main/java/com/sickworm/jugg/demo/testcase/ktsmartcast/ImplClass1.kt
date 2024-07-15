package com.sickworm.jugg.demo.testcase.ktsmartcast

class ImplClass1 {
    fun test(): String {
        val class2 = DataClass2()
        if (class2.dataList == null) {
            return "null"
        }
        return "size : ${class2.dataList.size}"
    }
}