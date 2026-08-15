package com.newoether.agora.ui.theme

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class UserMessageTypographyTest {
    @Test
    fun `user message body is one size step larger without changing line height`() {
        assertEquals(15.sp, ChatType.userBody.fontSize)
        assertEquals(22.sp, ChatType.userBody.lineHeight)
    }
}
