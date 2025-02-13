package com.memfault.bort

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RandomUuidDeviceIdProviderTest {
    lateinit var mockSharedPreferences: MockSharedPreferences

    @BeforeEach
    fun setUp() {
        mockSharedPreferences = makeFakeSharedPreferences()
    }

    @Test
    fun setsRandomUUIDWhenUninitialized() {
        assertNull(mockSharedPreferences.backingStorage[PREFERENCE_DEVICE_ID])
        val deviceIdProvider = RandomUuidDeviceIdProvider(mockSharedPreferences)

        val id = mockSharedPreferences.backingStorage[PREFERENCE_DEVICE_ID]
        assertNotNull(id)
        assertEquals(id, deviceIdProvider.deviceId())
    }

    @Test
    fun loadsIdFromSharedPreferences() {
        val id = "00000000-0000-0000-0000-000000000000"
        mockSharedPreferences.backingStorage[PREFERENCE_DEVICE_ID] = id

        val deviceIdProvider = RandomUuidDeviceIdProvider(mockSharedPreferences)
        assertEquals(id, deviceIdProvider.deviceId())
    }
}
