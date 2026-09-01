package com.sickworm.jugg.demo.testcase.membergenericsignature

class MemberGenericObserver {

    fun bind(provider: MemberGenericProvider) {
        provider.liveData.observe { }
    }
}
