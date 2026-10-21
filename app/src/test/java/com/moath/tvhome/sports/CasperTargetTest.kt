package com.moath.tvhome.sports

import com.moath.tvhome.system.CasperTarget
import com.moath.tvhome.system.CasperTuner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CasperTargetTest {
    @Test fun `filgoal channel names map to casper rows`() {
        assertEquals(CasperTarget("Bein Sports HD", "Bein Sports HD 1"), CasperTuner.targetFor("beIN SPORTS 1 HD"))
        assertEquals(CasperTarget("Bein Sports HD", "Bein Sports HD 6"), CasperTuner.targetFor("beIN SPORTS 6 HD"))
        assertEquals(CasperTarget("Bein Sports HD", "Bein Xtra HD 2"), CasperTuner.targetFor("beIN SPORTS XTRA 2"))
        assertEquals(CasperTarget("Thmanyah", null), CasperTuner.targetFor("SSC 1 HD"))
        assertNull(CasperTuner.targetFor("ESPN"))
    }
}
