package com.maeyaion.pos.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

public final class MainMenuDefinitionTest {
    @Test public void topLevelMenuNamesAndOrderRemainLocked() {
        assertEquals(Arrays.asList(
                "การขาย",
                "คลังสินค้า",
                "สร้างบาร์โค้ด",
                "ประวัติการขาย",
                "จัดการร้านค้า",
                "รายงานยอดขาย"), MainMenuDefinition.items());
        assertEquals(6, new HashSet<>(MainMenuDefinition.items()).size());
    }
}
