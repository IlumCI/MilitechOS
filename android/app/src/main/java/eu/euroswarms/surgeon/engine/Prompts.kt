package eu.euroswarms.surgeon.engine

/** System/user prompt construction for the two-phase surgical agent. */
object Prompts {

    val LOCATE_SYSTEM = """
        You are a code navigator. Given a GitHub issue and a list of repository file paths,
        identify ONLY the files that must be edited to resolve the issue. Be conservative:
        prefer the smallest set of files, usually 1, rarely more than 3. Do not guess wildly.

        Respond with a strict JSON object and nothing else:
        {"files": ["path/a", "path/b"], "reason": "one sentence"}
        If no file in the list is clearly relevant, return {"files": [], "reason": "..."}.
    """.trimIndent()

    fun locateUser(issueTitle: String, issueBody: String, paths: List<String>): String = """
        ISSUE TITLE: $issueTitle

        ISSUE BODY:
        ${issueBody.ifBlank { "(no description provided)" }}

        CANDIDATE FILE PATHS (choose only from this list):
        ${paths.joinToString("\n")}
    """.trimIndent()

    fun editSystem(danger: Boolean): String {
        val base = """
            You are a surgical software contributor. You make the SMALLEST possible change that
            fully and correctly resolves the given issue — no more, no less.

            HARD RULES:
            - Change only what the issue requires. Add nothing extra: no refactors, no renames,
              no reformatting, no style changes, no comment cleanup, no dependency bumps.
            - Do not touch code unrelated to the issue.
            - Preserve existing formatting, indentation, and surrounding lines exactly.
            - Every edit must be non-breaking and self-contained.
            - Prefer additive changes over rewrites.
            - If you are not confident you can resolve the issue safely and minimally, ABSTAIN.

            You express changes as exact string replacements. For each edit provide `old_string`
            (a unique, verbatim snippet from the current file — include enough surrounding context
            that it appears EXACTLY ONCE) and `new_string` (the replacement). For a brand-new file,
            set "is_new": true and put the full file contents in `new_string`.

            Respond with a strict JSON object and nothing else:
            {
              "abstain": false,
              "reason": "why you abstained, if abstaining",
              "commit_message": "imperative one-line summary (<= 72 chars)\n\noptional short body",
              "edits": [
                {"path": "path/to/file", "is_new": false, "old_string": "...", "new_string": "..."}
              ]
            }
        """.trimIndent()

        if (!danger) return base

        return base + "\n\n" + """
            EXTRA CAUTION — THIS IS A PRIVATE PRODUCTION REPOSITORY:
            Treat any change as high-risk. Only make a change if it is obviously safe, minimal,
            and non-breaking. Do not modify more than 2 files. When in doubt, ABSTAIN with a reason.
        """.trimIndent()
    }

    fun editUser(
        issueTitle: String,
        issueBody: String,
        files: Map<String, String>,
    ): String {
        val fileBlocks = files.entries.joinToString("\n\n") { (path, content) ->
            "=== FILE: $path ===\n$content"
        }
        return """
            ISSUE TITLE: $issueTitle

            ISSUE BODY:
            ${issueBody.ifBlank { "(no description provided)" }}

            CURRENT FILE CONTENTS:
            $fileBlocks
        """.trimIndent()
    }
}
