package dev.degoogle.app.root

/** Resultado de uma execução root. */
sealed class RootResult {
    abstract val exitCode: Int
    abstract val stdout: String
    abstract val stderr: String

    val succeeded: Boolean get() = exitCode == 0

    data class Ok(
        override val exitCode: Int,
        override val stdout: String,
        override val stderr: String,
    ) : RootResult()

    data class Error(
        val message: String,
        override val stderr: String = "",
    ) : RootResult() {
        override val exitCode: Int = -1
        override val stdout: String = ""
    }
}
