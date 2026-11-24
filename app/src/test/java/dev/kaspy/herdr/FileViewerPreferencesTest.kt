package dev.kaspy.herdr

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileViewerPreferencesTest {
    @Test
    fun defaultsAndSeparateWritesSurviveFreshViewerInstances() {
        val bytes = mutableMapOf<String, Boolean>()
        fun openViewer(): FileViewerPreferences = FileViewerPreferences(
            stub(SharedPreferences::class.java) { _, method, args ->
                when (method) {
                    "getBoolean" -> bytes[args[0] as String] ?: args[1] as Boolean
                    "edit" -> {
                        val pending = mutableMapOf<String, Boolean>()
                        stub(SharedPreferences.Editor::class.java) { editor, editMethod, editArgs ->
                            when (editMethod) {
                                "putBoolean" -> editor.also { pending[editArgs[0] as String] = editArgs[1] as Boolean }
                                "apply" -> bytes.putAll(pending)
                                else -> error("Unexpected editor operation: $editMethod")
                            }
                        }
                    }
                    else -> error("Unexpected preference operation: $method")
                }
            },
        )
        val first = openViewer()
        assertTrue(first.wrap())
        assertFalse(first.lineNumbers())

        first.setLineNumbers(true)
        val second = openViewer()
        assertTrue(second.wrap())
        assertTrue(second.lineNumbers())

        second.setWrap(false)
        val restored = openViewer()
        assertFalse(restored.wrap())
        assertTrue(restored.lineNumbers())

        restored.setLineNumbers(false)
        assertFalse(openViewer().wrap())
        assertFalse(openViewer().lineNumbers())
    }

    private fun <T> stub(type: Class<T>, call: (Any, String, Array<out Any?>) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { receiver, method, args ->
            call(receiver, method.name, args ?: emptyArray())
        })
}
