package com.sickworm.intellij.jugg.compiler

@Suppress("UNCHECKED_CAST")
/**
 * Result stores either a success payload or a failure payload in one container.
 */
class Result<Success, Failure> constructor(
    val isSuccess: Boolean,
    success: Success?,
    failure: Failure?,
    ) {

    private val value: Any? = if (isSuccess) success else failure

    val isFailed: Boolean get() = !isSuccess

    fun get(): Success = value as Success

    fun getFailure(): Failure = value as Failure

    fun getOrNull(): Success? = if (isSuccess) value as Success else null

    fun getFailureOrNull(): Failure? = if (isFailed) value as Failure else null

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
