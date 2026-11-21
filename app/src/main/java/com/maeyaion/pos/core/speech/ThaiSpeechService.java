package com.maeyaion.pos.core.speech;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Offline-only Thai TTS. Call close() from the owning component's lifecycle. */
public final class ThaiSpeechService implements AutoCloseable {
    public interface Listener {
        void onAvailabilityChanged(@NonNull ThaiVoiceSelector.Availability availability);
    }

    private final Context appContext;
    @Nullable private final Listener listener;
    @Nullable private TextToSpeech engine;
    @NonNull private volatile ThaiVoiceSelector.Availability availability =
            ThaiVoiceSelector.Availability.TEXT_ONLY;
    private volatile boolean initialized;
    private volatile boolean closed;
    private final ConcurrentMap<String, Runnable> completionCallbacks = new ConcurrentHashMap<>();

    public ThaiSpeechService(@NonNull Context context, @Nullable Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public synchronized void initialize() {
        if (closed || engine != null) return;
        engine = new TextToSpeech(appContext, status -> {
            if (closed) return;
            if (status != TextToSpeech.SUCCESS) {
                publish(ThaiVoiceSelector.Availability.TEXT_ONLY);
                return;
            }
            configureOfflineThaiVoice();
        });
    }

    private void configureOfflineThaiVoice() {
        TextToSpeech current = engine;
        if (current == null || closed) return;
        Set<Voice> voices = current.getVoices();
        List<ThaiVoiceSelector.Candidate> candidates = new ArrayList<>();
        if (voices != null) {
            for (Voice voice : voices) {
                candidates.add(new ThaiVoiceSelector.Candidate(
                        voice.getName(), voice.getLocale().toLanguageTag(),
                        voice.isNetworkConnectionRequired()));
            }
        }
        ThaiVoiceSelector.Selection selection = ThaiVoiceSelector.select(candidates);
        if (selection.candidate == null) {
            initialized = false;
            publish(ThaiVoiceSelector.Availability.TEXT_ONLY);
            return;
        }
        current.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }
            @Override public void onDone(String utteranceId) { completeUtterance(utteranceId); }
            @Override public void onError(String utteranceId) { completeUtterance(utteranceId); }
            @Override public void onStop(String utteranceId, boolean interrupted) { completeUtterance(utteranceId); }
        });
        Voice selected = null;
        if (voices != null) {
            for (Voice voice : voices) {
                if (voice.getName().equals(selection.candidate.name)) {
                    selected = voice;
                    break;
                }
            }
        }
        if (selected == null || current.setVoice(selected) != TextToSpeech.SUCCESS) {
            initialized = false;
            publish(ThaiVoiceSelector.Availability.TEXT_ONLY);
            return;
        }
        int languageResult = current.setLanguage(selected.getLocale());
        if (languageResult < TextToSpeech.LANG_AVAILABLE) {
            initialized = false;
            publish(ThaiVoiceSelector.Availability.TEXT_ONLY);
            return;
        }
        initialized = true;
        publish(selection.availability);
    }

    private void publish(@NonNull ThaiVoiceSelector.Availability next) {
        availability = next;
        Listener target = listener;
        if (target != null && !closed) target.onAvailabilityChanged(next);
    }

    @NonNull
    public ThaiVoiceSelector.Availability getAvailability() {
        return availability;
    }

    /** Returns false when Thai offline TTS is unavailable or the engine rejects the utterance. */
    public boolean speak(@NonNull String text, @NonNull String utteranceId) {
        return speak(text, utteranceId, null);
    }

    /** Queues speech and invokes completion on the TTS callback thread, including error/stop. */
    public boolean speak(@NonNull String text, @NonNull String utteranceId, @Nullable Runnable onComplete) {
        TextToSpeech current = engine;
        if (closed || !initialized || current == null || text.trim().isEmpty()) return false;
        if (onComplete != null) completionCallbacks.put(utteranceId, onComplete);
        Bundle parameters = new Bundle();
        int result = current.speak(text, TextToSpeech.QUEUE_ADD, parameters, utteranceId);
        if (result != TextToSpeech.SUCCESS && onComplete != null) completionCallbacks.remove(utteranceId);
        return result == TextToSpeech.SUCCESS;
    }

    private void completeUtterance(String utteranceId) {
        Runnable callback = completionCallbacks.remove(utteranceId);
        if (callback != null) callback.run();
    }

    @Override public synchronized void close() {
        closed = true;
        initialized = false;
        completionCallbacks.clear();
        if (engine != null) {
            engine.stop();
            engine.shutdown();
            engine = null;
        }
    }
}
