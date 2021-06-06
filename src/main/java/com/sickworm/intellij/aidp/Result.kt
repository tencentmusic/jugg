package com.sickworm.intellij.aidp

@Suppress("UNCHECKED_CAST")
class Result<Success, Failure> private constructor(
    val isSuccess: Boolean,
    success: Success?,
    failure: Failure?,
    ) {

    private val value: Any? = if (isSuccess) success else failure

    val isFailure: Boolean get() = !isSuccess

    fun get(): Success = value as Success

    fun getFailure(): Failure = value as Failure

    fun getOrNull(): Success? = if (isSuccess) value as Success else null

    fun getFailureOrNull(): Failure? = if (isFailure) value as Failure else null

    override fun toString(): String {
        val result = if (isSuccess) "success" else "failure"
        return "$result:$value"
    }

    companion object {
        fun <Success, Failure> success(success: Success): Result<Success, Failure> {
            return Result(true, success, null)
        }

        fun <Success, Failure> failure(failure: Failure): Result<Success, Failure> {
            return Result(false, null, failure)
        }
    }
}