package com.maeyaion.pos.app;

/** Converts cryptographic restore failures into safe, understandable Thai messages. */
public final class BackupErrorMessages {
    private BackupErrorMessages() {}

    public static String toUserMessage(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 12; depth++, current = current.getCause()) {
            String type = current.getClass().getSimpleName();
            String detail = current.getMessage() == null
                    ? "" : current.getMessage().toLowerCase(java.util.Locale.ROOT);
            if ("AEADBadTagException".equals(type)
                    || "BadPaddingException".equals(type)
                    || detail.contains("bad_decrypt")
                    || detail.contains("tag mismatch")
                    || detail.contains("mac check")) {
                return "Recovery Password หรือ Recovery Key ไม่ถูกต้อง หรือไฟล์สำรองเสียหาย";
            }
            if (detail.contains("unknown backup format")
                    || detail.contains("unsupported backup version")) {
                return "ไฟล์สำรองไม่รองรับหรือรูปแบบไฟล์ไม่ถูกต้อง";
            }
            if (detail.contains("sqlite") || detail.contains("schema version")) {
                return "ข้อมูลสำรองไม่ตรงกับโครงสร้างฐานข้อมูลของแอป";
            }
        }
        return "กู้คืนข้อมูลไม่สำเร็จ ข้อมูลเดิมยังคงอยู่ กรุณาตรวจสอบไฟล์สำรองและข้อมูลกู้คืน";
    }
}
