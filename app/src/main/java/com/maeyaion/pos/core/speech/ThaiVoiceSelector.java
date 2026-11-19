package com.maeyaion.pos.core.speech;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Best-effort offline Thai voice selection; Android does not expose standardized voice gender metadata. */
public final class ThaiVoiceSelector {
    public enum Availability { THAI_FEMALE_PREFERRED, THAI_DEFAULT, TEXT_ONLY }

    public static final class Candidate {
        @NonNull public final String name;
        @NonNull public final String languageTag;
        public final boolean networkRequired;

        public Candidate(@NonNull String name, @NonNull String languageTag, boolean networkRequired) {
            this.name = name;
            this.languageTag = languageTag;
            this.networkRequired = networkRequired;
        }
    }

    public static final class Selection {
        @NonNull public final Availability availability;
        public final Candidate candidate;

        private Selection(@NonNull Availability availability, Candidate candidate) {
            this.availability = availability;
            this.candidate = candidate;
        }
    }

    private ThaiVoiceSelector() {}

    @NonNull
    public static Selection select(@NonNull List<Candidate> candidates) {
        List<Candidate> offlineThai = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.networkRequired) continue;
            Locale locale = Locale.forLanguageTag(candidate.languageTag);
            if ("th".equalsIgnoreCase(locale.getLanguage())) offlineThai.add(candidate);
        }
        if (offlineThai.isEmpty()) return new Selection(Availability.TEXT_ONLY, null);

        offlineThai.sort(Comparator.comparing(c -> c.name.toLowerCase(Locale.ROOT)));
        for (Candidate candidate : offlineThai) {
            if (hasFemaleHint(candidate.name)) {
                return new Selection(Availability.THAI_FEMALE_PREFERRED, candidate);
            }
        }
        return new Selection(Availability.THAI_DEFAULT, offlineThai.get(0));
    }

    private static boolean hasFemaleHint(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        return value.contains("female") || value.contains("woman")
                || value.contains("fem") || value.contains("th-f")
                || value.contains("th_f");
    }
}
