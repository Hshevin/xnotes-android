package com.xnotes.storage

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class RegistryVersionPolicyTest {
    @Test fun futureSchemaRetainsRecoverableInformation()=RegistryVersionAssertions.refusesFutureSchemaWithoutDroppingRecovery(RuntimeEnvironment.getApplication())
}
