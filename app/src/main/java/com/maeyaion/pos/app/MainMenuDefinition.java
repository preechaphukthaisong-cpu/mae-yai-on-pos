package com.maeyaion.pos.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Single source of truth for the six locked top-level POS menu labels and order. */
public final class MainMenuDefinition {
    private static final List<String> ITEMS = Collections.unmodifiableList(Arrays.asList(
            "การขาย",
            "คลังสินค้า",
            "สร้างบาร์โค้ด",
            "ประวัติการขาย",
            "จัดการร้านค้า",
            "รายงานยอดขาย"));

    private MainMenuDefinition() {}

    public static List<String> items() {
        return ITEMS;
    }
}
