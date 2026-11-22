package com.vknxworks.recorderctrl.record

import org.junit.Assert.assertEquals
import org.junit.Test

class PartFateTest {
    @Test fun discardedWhenYouDontKeepIt() {
        assertEquals(PartFate.DISCARD, PartFate.of(save = false, stopped = true))
        assertEquals(PartFate.DISCARD, PartFate.of(save = false, stopped = false))
    }

    @Test fun savedWhenStoppedCleanly() {
        assertEquals(PartFate.SAVE, PartFate.of(save = true, stopped = true))
    }

    @Test fun keptIfReadableWhenTheRecorderFailedToStop() {
        assertEquals(PartFate.SALVAGE, PartFate.of(save = true, stopped = false))
    }
}
