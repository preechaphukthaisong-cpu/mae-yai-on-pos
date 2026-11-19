package com.maeyaion.pos.app;

import org.junit.Test;

import java.security.GeneralSecurityException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class BackupErrorMessagesTest {
    @Test
    public void badDecryptUsesThaiMessageWithoutOpenSslDetails() {
        String message = BackupErrorMessages.toUserMessage(
                new GeneralSecurityException("error:1e000065:Cipher functions:OPENSSL_internal:BAD_DECRYPT"));
        assertTrue(message.contains("ไม่ถูกต้อง"));
        assertTrue(message.contains("ไฟล์สำรองเสียหาย"));
        assertTrue(!message.contains("OPENSSL"));
    }

    @Test
    public void unknownFormatUsesSpecificThaiMessage() {
        assertEquals("ไฟล์สำรองไม่รองรับหรือรูปแบบไฟล์ไม่ถูกต้อง",
                BackupErrorMessages.toUserMessage(
                        new GeneralSecurityException("Unknown backup format")));
    }
}
