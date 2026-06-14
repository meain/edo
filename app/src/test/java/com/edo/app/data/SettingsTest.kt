package com.edo.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {

    @Test
    fun providerFromNameDefaultsToAnthropic() {
        assertEquals(Provider.Anthropic, Provider.fromName(null))
        assertEquals(Provider.Anthropic, Provider.fromName("garbage"))
        assertEquals(Provider.OpenAI, Provider.fromName("OpenAI"))
        assertEquals(Provider.Anthropic, Provider.fromName("Anthropic"))
    }

    @Test
    fun inMemorySettingsStoreRoundTrips() {
        val store = InMemorySettingsStore()
        val updated = AppSettings(
            provider = Provider.OpenAI,
            baseUrl = "https://api.example.com",
            apiKey = "k",
            model = "gpt-4o-mini",
            activeProjectId = 42L,
        )
        store.save(updated)
        assertEquals(updated, store.load())
    }
}
