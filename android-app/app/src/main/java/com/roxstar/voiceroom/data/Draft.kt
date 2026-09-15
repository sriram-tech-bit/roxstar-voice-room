package com.roxstar.voiceroom.data

data class Draft(
    val id: String,
    val name: String,
    val createdAt: Long,
    val durationMs: Long,
    val path: String,
    val effect: String = "echo"
)
