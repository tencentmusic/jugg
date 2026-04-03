package com.sickworm.jugg.demo.testcase.ktcompanionext

class CompanionExtInvoker {
    fun invoke(): String {
        return PlayerDefine.State.toString(1)
    }
}
