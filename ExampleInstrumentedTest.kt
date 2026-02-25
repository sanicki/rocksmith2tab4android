package com.rocksmithtab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests — run on a real device or emulator via:
 *   ./gradlew connectedAndroidTest
 *
 * Use these for tests that need an Android Context (e.g. reading files via
 * ContentResolver, verifying WorkManager behaviour, or UI tests with Espresso).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @Test
    fun appPackageNameIsCorrect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.rocksmithtab", context.packageName)
    }
}
