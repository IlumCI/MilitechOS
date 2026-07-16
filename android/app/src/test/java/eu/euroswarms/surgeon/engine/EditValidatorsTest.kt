package eu.euroswarms.surgeon.engine

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditValidatorsTest {

    @Test
    fun blankContentRejected() {
        assertNotNull(EditValidators.validate("any.txt", "   \n  "))
    }

    @Test
    fun invalidJsonRejected() {
        val error = EditValidators.validate("package.json", "{\"a\": }")
        assertNotNull(error)
        assertTrue(error!!.contains("JSON"))
    }

    @Test
    fun validJsonPasses() {
        assertNull(EditValidators.validate("package.json", "{\"a\": 1, \"b\": [true, null]}"))
    }

    @Test
    fun yamlTabIndentationRejected() {
        val error = EditValidators.validate("ci.yml", "jobs:\n\tbuild:\n\t\truns-on: ubuntu\n")
        assertNotNull(error)
        assertTrue(error!!.contains("tab"))
    }

    @Test
    fun yamlWithSpacesPasses() {
        assertNull(EditValidators.validate("ci.yaml", "jobs:\n  build:\n    runs-on: ubuntu\n"))
    }

    @Test
    fun ordinaryTextFilePassesWhenNonBlank() {
        assertNull(EditValidators.validate("README.md", "# Title\ncontent\n"))
    }
}
