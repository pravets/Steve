package com.steve.ai.client;

import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.network.ServerboundVoiceChunkPacket;
import com.steve.ai.network.SteveNetworking;
import com.steve.ai.voice.WavBuilder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Client-side microphone recorder (push-to-talk, key V).
 *
 * <p>Captures 16 kHz / 16-bit / mono PCM via the standard Java Sound API,
 * assembles a WAV file and streams it to the server in chunks.</p>
 */
public final class VoiceRecorder {

    private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);
    private static final int MIN_RECORDING_BYTES = 16000; // ~0.5s minimum at 32 KB/s

    private static volatile boolean recording = false;
    private static volatile long startedAtMillis = 0;
    private static TargetDataLine line;
    private static ByteArrayOutputStream buffer;
    private static Thread captureThread;

    private VoiceRecorder() {}

    public static boolean isRecording() {
        return recording;
    }

    public static long startedAtMillis() {
        return startedAtMillis;
    }

    /** Toggles recording; returns false if the microphone cannot be opened. */
    public static boolean toggle() {
        return recording ? stop() : start();
    }

    public static boolean start() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                SteveMod.LOGGER.warn("No microphone line for {}", FORMAT);
                return false;
            }
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(FORMAT);
            buffer = new ByteArrayOutputStream();
            recording = true;
            startedAtMillis = System.currentTimeMillis();

            captureThread = new Thread(() -> {
                byte[] chunk = new byte[4096];
                while (recording) {
                    int n = line.read(chunk, 0, chunk.length);
                    if (n > 0) {
                        synchronized (buffer) {
                            buffer.write(chunk, 0, n);
                        }
                    }
                }
            }, "steve-voice-capture");
            captureThread.setDaemon(true);
            captureThread.start();
            line.start();
            return true;
        } catch (Exception e) {
            SteveMod.LOGGER.warn("Failed to open microphone: {}", e.toString());
            recording = false;
            return false;
        }
    }

    public static boolean stop() {
        if (!recording) {
            return true;
        }
        recording = false;
        // Stop the line BEFORE joining: line.read() can block forever otherwise
        if (line != null) {
            try {
                line.stop();
            } catch (Exception ignored) {
            }
        }
        try {
            if (captureThread != null) {
                captureThread.join(1000);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (line != null) {
            try {
                line.close();
            } catch (Exception ignored) {
            }
            line = null;
        }

        byte[] pcm;
        synchronized (buffer) {
            pcm = buffer.toByteArray();
            buffer = null;
        }
        if (pcm.length < MIN_RECORDING_BYTES) {
            return true; // too short - discard silently
        }
        sendChunks(WavBuilder.buildWav(pcm));
        return true;
    }

    /** Auto-stop when the configured max recording time is exceeded. */
    public static void checkAutoStop() {
        if (recording && System.currentTimeMillis() - startedAtMillis
                > SteveConfig.VOICE_MAX_RECORDING_SECONDS.get() * 1000L) {
            stop();
        }
    }

    private static void sendChunks(byte[] wav) {
        int chunkSize = SteveConfig.VOICE_CHUNK_SIZE.get();
        int chunks = (wav.length + chunkSize - 1) / chunkSize;
        for (int i = 0; i < chunks; i++) {
            int off = i * chunkSize;
            int len = Math.min(chunkSize, wav.length - off);
            byte[] part = Arrays.copyOfRange(wav, off, off + len);
            SteveNetworking.CHANNEL.sendToServer(new ServerboundVoiceChunkPacket(part, i, i == chunks - 1));
        }
        SteveMod.LOGGER.info("Voice: sent {} bytes in {} chunk(s)", wav.length, chunks);
    }
}
