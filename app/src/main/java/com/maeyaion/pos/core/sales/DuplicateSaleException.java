package com.maeyaion.pos.core.sales;

/** Signals a matching completed bill within the duplicate-warning window. */
public final class DuplicateSaleException extends IllegalStateException {
    public final String existingSaleId;
    public DuplicateSaleException(String existingSaleId) {
        super("พบรายการขายที่มีรายละเอียดเหมือนกันภายใน 60 นาที: " + existingSaleId);
        this.existingSaleId = existingSaleId;
    }
}
