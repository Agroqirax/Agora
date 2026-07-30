package com.newoether.agora.data

object DefaultSkills {
    data class Builtin(
        val name: String,
        val description: String,
        val content: String
    )

    val BUILTINS: List<Builtin> = emptyList()
}
