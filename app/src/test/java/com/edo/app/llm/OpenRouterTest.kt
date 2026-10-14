package com.edo.app.llm

import com.edo.app.data.OpenRouterOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterTest {
    @Test
    fun defaultsSendNothingExtra() {
        assertTrue(OpenRouterOptions().requestExtras("m").isEmpty())
    }

    @Test
    fun providerRoutingFields() {
        val extras = OpenRouterOptions(
            onlyProviders = "deepinfra, together",
            sort = "price",
            allowFallbacks = false,
            denyDataCollection = true,
        ).requestExtras("m")
        val provider = extras["provider"]!!.jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("deepinfra"), JsonPrimitive("together"))), provider["only"])
        assertEquals("price", provider["sort"]!!.jsonPrimitive.content)
        assertFalse(provider["allow_fallbacks"]!!.jsonPrimitive.boolean)
        assertEquals("deny", provider["data_collection"]!!.jsonPrimitive.content)
    }

    @Test
    fun fallbackModelsStartWithTheMainModel() {
        val extras = OpenRouterOptions(fallbackModels = "b, c").requestExtras("a")
        assertEquals(JsonArray(listOf("a", "b", "c").map(::JsonPrimitive)), extras["models"])
    }

    @Test
    fun customJsonOverridesAndBadJsonIsIgnored() {
        val merged = OpenRouterOptions(reasoningEffort = "low", extraJson = """{"temperature":0.2}""").requestExtras("m")
        assertEquals("0.2", merged["temperature"]!!.jsonPrimitive.content)
        assertTrue("reasoning" in merged)
        assertTrue("reasoning" in OpenRouterOptions(reasoningEffort = "low", extraJson = "{oops").requestExtras("m"))
    }

    @Test
    fun extrasAreMergedIntoTheRequestBody() {
        val extra = OpenRouterOptions(sort = "latency").requestExtras("m")
        val body = Json.parseToJsonElement(OpenAIClient.buildRequestJson("m", null, emptyList(), emptyList(), extra)).jsonObject
        assertEquals("m", body["model"]!!.jsonPrimitive.content)
        assertEquals("latency", body["provider"]!!.jsonObject["sort"]!!.jsonPrimitive.content)
    }
}
