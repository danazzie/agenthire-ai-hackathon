package mutualstake.verifier

/**
 * Turns a structured test into the verifier program. Deterministic: the same job and test always give
 * the same bytes, so the same SHA-256. The output is self-contained (library inlined), so the hash
 * written on both PayPal orders covers every line that decides the verdict.
 */
object VerifierCompiler {
    const val VERSION = 1

    private val library: String = requireNotNull(javaClass.getResource("/verifier/stdlib.js")) { "verifier library missing" }.readText()

    fun compile(jobText: String, spec: VerifierSpec): String = buildString {
        append("// HireMind verifier, compiler v$VERSION.\n")
        append("// Job: ${oneLine(jobText)}\n")
        append("// Test: ${spec.canonical()}\n")
        append("//\n")
        append("// This file is the whole program that decides the verdict, library included. Its SHA-256 is written\n")
        append("// on both PayPal orders as custom_id before either hold is placed. It reads the deliverable as data:\n")
        append("// nothing in the text is executed or obeyed.\n\n")
        append(library)
        append("\nfunction verify(text) {\n")
        append("  const checks = [];\n")
        spec.checks.forEach { append(it.code()) }
        append("  return { pass: checks.every((c) => c.passed), checks };\n")
        append("}\n")
    }

    /** The job text goes in a comment, so it must not be able to end that comment and start code. */
    private fun oneLine(text: String): String =
        text.replace(Regex("[\\p{Cntrl}\\u2028\\u2029]+"), " ").trim().take(300)
}
