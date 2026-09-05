package com.sickworm.jugg.demo.testcase.hilt

import javax.inject.Inject

/** Injectable value used to prove that Hilt entry point injection remains active. */
class HiltValue @Inject constructor() {
    val text = "injected"
}
