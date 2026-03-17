package com.sickworm.jugg.demo.testcase.lambdaparent

/**
 * Test fixture for the static lambda cascade bug.
 *
 * When D8 compiles this class in --file-per-class mode, it generates
 * ExternalSyntheticLambda classes whose <init> is static-dispatch only.
 * Modifying the lambda count causes these synthetic class signatures to shift,
 * which used to incorrectly trigger recompilation of all subclasses.
 */
open class LambdaParent {

    // Multiple lambdas so D8 generates several ExternalSyntheticLambda entries.
    // Adding / removing a lambda here causes lambda renumbering in the compiled output.
    private val action1: Runnable = Runnable { doWork("action1") }
    private val action2: Runnable = Runnable { doWork("action2") }

    open fun onAction() {
        action1.run()
    }

    private fun doWork(tag: String) {
        println("LambdaParent doWork: $tag")
    }
}
