package com.sickworm.intellij.aidp

@Suppress("UNCHECKED_CAST")
class Result<Success, Failure> private constructor(
    val isSuccess: Boolean,
    success: Success?,
    failure: Failure?,
    ) {

    private val value: Any? = if (isSuccess) success else failure

    val isFailure: Boolean get() = !isSuccess

    fun getOrNull(): Success? = if (isSuccess) value as Success else null

    fun getFailureOrNull(): Failure? = if (isFailure) value as Failure else null

    companion object {
        fun <Success, Failure> success(success: Success): Result<Success, Failure> {
            return Result(true, success, null)
        }

        fun <Success, Failure> failure(failure: Failure): Result<Success, Failure> {
            return Result(false, null, failure)
        }
    }
}