package com.sickworm.jugg.demo.testcase

class CaseKtSmartCast {
    fun test(): String {
        val class2 = CaseKtSmartCast2()
        if (class2.dataList == null) {
            return "null"
        }
        return "size : ${class2.dataList.size}"
    }
}