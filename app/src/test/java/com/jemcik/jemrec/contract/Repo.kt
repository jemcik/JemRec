package com.jemcik.jemrec.contract

import java.io.File

/**
 * The repository these tests run in. Gradle runs a module's unit tests in the
 * module's folder, so the root is found by walking up to settings.gradle.kts
 * rather than assumed.
 *
 * The sources are read whole, not file by file: the 1.0 restructuring moves
 * files between packages, and a contract test that named a file would fail on
 * a move that changed nothing it guards. And they are read without their
 * comments: a KDoc that quotes a log line would keep its test green after the
 * line itself was gone.
 */
internal object Repo {
    val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    fun file(path: String) = File(root, path)

    fun text(path: String) = file(path).readText()

    private fun tree(dir: String): String =
        file(dir).walk().filter { it.extension == "kt" || it.extension == "java" }
            .sortedBy { it.path }
            .joinToString("\n") { code(it.readText(), kotlin = it.extension == "kt") }

    /** The app's own code, as a release has it. */
    val app: String by lazy { tree("app/src/main/java") }

    /** The recorder's code (shellserver/src), scrcpy's included. */
    val recorder: String by lazy { tree("shellserver/src") }

    /** All the code a phone runs: the app, its debug build (which the
     *  emulator checks install), and the recorder. */
    val sources: String by lazy { listOf(app, tree("app/src/debug/java"), recorder).joinToString("\n") }

    /**
     * [text] without its comments. Strings are kept whole, a comment's
     * opening inside one included (a content URI's two slashes, a MIME
     * type's slash and star), and so are Kotlin's raw strings, Java's text
     * blocks and char literals, so a quote character opens nothing. Kotlin's
     * block comments nest, Java's do not: [kotlin] says which.
     */
    fun code(text: String, kotlin: Boolean): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        fun keep(end: Int) {
            out.append(text, i, end)
            i = end
        }
        val open = "/" + "*"
        val close = "*" + "/"
        while (i < n) {
            when {
                text.startsWith("\"\"\"", i) -> {
                    // Up to its last quote: a raw string can end with more than three.
                    var end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it + 3 }
                    while (end < n && text[end] == '"') end++
                    keep(end)
                }
                text[i] == '"' -> {
                    var j = i + 1
                    while (j < n && text[j] != '"' && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
                    keep(minOf(j + 1, n))
                }
                text[i] == '\'' -> {
                    val from = i + if (i + 1 < n && text[i + 1] == '\\') 3 else 2
                    keep(text.indexOf('\'', from).let { if (it < 0) n else it + 1 })
                }
                text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) n else it }
                text.startsWith(open, i) -> {
                    var depth = 0
                    while (i < n) {
                        when {
                            text.startsWith(open, i) && (kotlin || depth == 0) -> {
                                depth++
                                i += 2
                            }
                            text.startsWith(close, i) -> {
                                i += 2
                                if (--depth == 0) break
                            }
                            else -> i++
                        }
                    }
                    out.append(' ')
                }
                else -> keep(i + 1)
            }
        }
        return out.toString()
    }
}
