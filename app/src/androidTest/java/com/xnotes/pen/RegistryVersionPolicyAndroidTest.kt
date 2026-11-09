package com.xnotes.pen

import com.xnotes.storage.RegistryVersionAssertions
import org.junit.Test

class RegistryVersionPolicyAndroidTest {
    @Test fun futureSchemaRetainsRecoverableInformation()=RegistryVersionAssertions.refusesFutureSchemaWithoutDroppingRecovery(PenTestRunner.instance.targetContext)
}
