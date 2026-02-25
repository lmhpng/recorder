package com.voicerecorder.app

data class Recording(
    val id: String,
    val name: String,
    val filePath: String,
    val duration: String,
    val date: String,
    val transcript: String? = null,
    val summary: String? = null
)
