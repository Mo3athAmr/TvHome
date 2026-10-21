package com.moath.tvhome.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ArabicTextTest {
    @Test fun `digits become arabic-indic`() = assertEquals("٨١٬٠٠٢", ArabicText.grouped(81002))
    @Test fun `duration matches the reference wording`() = assertEquals("٨ ساعات و٤٤ دقيقة", ArabicText.duration(8 * 60 + 44))
    @Test fun `minutes only`() = assertEquals("٥٧ دقيقة", ArabicText.duration(57))
    @Test fun `dual forms`() = assertEquals("ساعتان ودقيقتان", ArabicText.duration(122))
    @Test fun `few plural`() = assertEquals("٣ ساعات و٥ دقائق", ArabicText.duration(185))
    @Test fun `days many`() = assertEquals("١١٣ يومًا", ArabicText.days(113))
    @Test fun `evening clock`() = assertEquals("٧:٥٢ م", ArabicText.time(19 * 60 + 52))
    @Test fun `fajr clock`() = assertEquals("٤:٣٥ ص", ArabicText.time(4 * 60 + 35))
    @Test fun `noon is pm`() = assertEquals("١٢:٠٠ م", ArabicText.time(12 * 60))
    @Test fun `date line`() = assertEquals("الأحد، ١٨ أكتوبر", ArabicText.gregorian(0, 18, 9))
    @Test fun `rounded thousands`() = assertEquals("١٤ ألف", ArabicText.followers(14_000, exact = false))
    @Test fun `rounded with decimal`() = assertEquals("١٤٫١ ألف", ArabicText.followers(14_100, exact = false))
    @Test fun `exact small count`() = assertEquals("٩٬٨٧٦", ArabicText.followers(9_876, exact = true))
    @Test fun `percent`() = assertEquals("٠٫٣", ArabicText.oneDecimal(-0.3))
}
