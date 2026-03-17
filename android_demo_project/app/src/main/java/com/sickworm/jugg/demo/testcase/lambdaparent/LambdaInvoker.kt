package com.sickworm.jugg.demo.testcase.lambdaparent

/**
 * Calls LambdaParent.onAction() via virtual dispatch.
 * Should be recompiled when onAction() signature changes, but NOT when
 * only LambdaParent's internal lambda numbering shifts.
 */
class LambdaInvoker {

    fun invoke(parent: LambdaParent) {
        parent.onAction()
    }
}
