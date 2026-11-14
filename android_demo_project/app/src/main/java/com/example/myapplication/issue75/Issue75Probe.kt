package com.example.myapplication.issue75

fun issue75Probe(value: ChildModel): String {
    value._id = 1L
    value.title = "baseline"
    value.docType = 1
    return value.title
}
