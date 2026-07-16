package eu.euroswarms.surgeon.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineUtilsTest {

    @Test
    fun slugifyBasic() {
        assertEquals("fix-the-docs", EngineUtils.slugify("Fix the docs"))
    }

    @Test
    fun slugifyStripsSpecialCharsAndCollapsesDashes() {
        assertEquals("fix-bug-in-parser", EngineUtils.slugify("Fix!! bug -- in <parser>"))
    }

    @Test
    fun slugifyCapsLengthAt40() {
        val slug = EngineUtils.slugify("a".repeat(100))
        assertTrue(slug.length <= 40)
    }

    @Test
    fun slugifyBlankFallsBack() {
        assertEquals("change", EngineUtils.slugify("!!!"))
    }

    @Test
    fun normalizeLabelHandlesSpacesAndUnderscores() {
        assertEquals("on-hold", EngineUtils.normalizeLabel("On Hold"))
        assertEquals("needs-discussion", EngineUtils.normalizeLabel("needs_discussion"))
        assertEquals("wontfix", EngineUtils.normalizeLabel("WONTFIX"))
    }

    @Test
    fun skipLabelsCatchCommonDisqualifiers() {
        assertTrue(EngineUtils.normalizeLabel("wontfix") in EngineUtils.SKIP_LABELS)
        assertTrue(EngineUtils.normalizeLabel("Question") in EngineUtils.SKIP_LABELS)
    }

    @Test
    fun rankPathsPutsFilenameMatchesFirst() {
        val paths = listOf(
            "zzz/aaa.py",
            "docs/config.md",
            "src/config_loader.py",
        )
        val ranked = EngineUtils.rankPaths(paths, "Fix config loader crash", "", cap = 10)
        // "config_loader.py" matches both "config" and "loader" in the filename.
        assertEquals("src/config_loader.py", ranked.first())
    }

    @Test
    fun rankPathsRespectsCap() {
        val paths = (1..50).map { "file$it.py" }
        assertEquals(5, EngineUtils.rankPaths(paths, "title", "body", cap = 5).size)
    }

    @Test
    fun rankPathsWithNoTokensStillCaps() {
        val paths = (1..10).map { "f$it.py" }
        assertEquals(3, EngineUtils.rankPaths(paths, "a b", "", cap = 3).size)
    }
}
