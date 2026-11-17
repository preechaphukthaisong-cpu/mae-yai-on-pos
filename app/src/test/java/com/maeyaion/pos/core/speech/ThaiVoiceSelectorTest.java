package com.maeyaion.pos.core.speech;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class ThaiVoiceSelectorTest {
    @Test public void prefersOfflineThaiVoiceWithFemaleNameHint() {
        ThaiVoiceSelector.Selection result = ThaiVoiceSelector.select(Arrays.asList(
                new ThaiVoiceSelector.Candidate("th-default", "th-TH", false),
                new ThaiVoiceSelector.Candidate("th-female-local", "th-TH", false),
                new ThaiVoiceSelector.Candidate("th-female-network", "th-TH", true)));
        assertEquals(ThaiVoiceSelector.Availability.THAI_FEMALE_PREFERRED, result.availability);
        assertNotNull(result.candidate);
        assertEquals("th-female-local", result.candidate.name);
    }

    @Test public void fallsBackToOfflineThaiDefaultWhenNoFemaleHintExists() {
        ThaiVoiceSelector.Selection result = ThaiVoiceSelector.select(Arrays.asList(
                new ThaiVoiceSelector.Candidate("th-z", "th-TH", false),
                new ThaiVoiceSelector.Candidate("th-a", "th", false)));
        assertEquals(ThaiVoiceSelector.Availability.THAI_DEFAULT, result.availability);
        assertEquals("th-a", result.candidate.name);
    }

    @Test public void neverSelectsNetworkVoiceAndUsesTextOnlyWhenNoOfflineThaiVoiceExists() {
        ThaiVoiceSelector.Selection result = ThaiVoiceSelector.select(Collections.singletonList(
                new ThaiVoiceSelector.Candidate("th-female-network", "th-TH", true)));
        assertEquals(ThaiVoiceSelector.Availability.TEXT_ONLY, result.availability);
        assertNull(result.candidate);
    }

    @Test public void ignoresNonThaiOfflineVoices() {
        ThaiVoiceSelector.Selection result = ThaiVoiceSelector.select(Collections.singletonList(
                new ThaiVoiceSelector.Candidate("en-female", "en-US", false)));
        assertEquals(ThaiVoiceSelector.Availability.TEXT_ONLY, result.availability);
    }
}
