package dev.kaspy.herdr

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

class AgentFaceAssetsTest {
    @Test fun bundledFacesAreSingleUntransformedVectorPaths() {
        val vectors = File("src/main/res/drawable").listFiles { file ->
            file.name.startsWith("ic_agent_face_") && file.extension == "xml"
        }.orEmpty()
        assertEquals(AgentAvatarDesign.FACE_COUNT, vectors.size)
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (file in vectors) {
            val root = xml.parse(file).documentElement
            assertEquals("vector", root.tagName)
            assertEquals("24", root.getAttribute("android:viewportWidth"))
            assertEquals("24", root.getAttribute("android:viewportHeight"))
            val children = (0 until root.childNodes.length)
                .map { root.childNodes.item(it) }.filterIsInstance<Element>()
            assertEquals(file.name, 1, children.size)
            assertEquals("path", children.single().tagName)
            assertFalse(children.single().getAttribute("android:pathData").isBlank())
        }
    }
}
