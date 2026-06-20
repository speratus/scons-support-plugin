package space.luchuktech.sconssupport

import org.junit.Assert.assertEquals
import org.junit.Test
import space.luchuktech.sconssupport.introspection.SConsModelBuilder

class SConsModelBuilderTest {
    @Test
    fun testParseSimpleModel() {
        val json = """
---SCONS_INTROSPECT_BEGIN---
{
  "schema_version": 1,
  "targets": [
    {
      "name": "hello",
      "type": "Program",
      "sources": ["main.c"],
      "env": {"CC": "gcc"}
    }
  ],
  "options": [
    {
      "key": "debug",
      "help": "debug help",
      "default": "0",
      "type": "bool"
    }
  ]
}
---SCONS_INTROSPECT_END---
        """.trimIndent()
        
        val model = SConsModelBuilder.parse(json)
        assertEquals(1, model.targets.size)
        assertEquals("hello", model.targets[0].name)
        assertEquals("Program", model.targets[0].type)
        assertEquals(1, model.options.size)
        assertEquals("debug", model.options[0].key)
    }
}
