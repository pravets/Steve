package com.steve.ai.voice;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class WavBuilderTest {

    @Test
    void headerIsCanonicalRiffWav() {
        byte[] pcm = new byte[32000]; // 1 second of 16 kHz 16-bit mono
        byte[] wav = WavBuilder.buildWav(pcm);

        assertEquals(44 + pcm.length, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4));
        assertEquals("WAVE", new String(wav, 8, 4));
        assertEquals("fmt ", new String(wav, 12, 4));
        assertEquals("data", new String(wav, 36, 4));
    }

    @Test
    void riffSizeAndDataSizeMatch() {
        byte[] pcm = new byte[8000];
        byte[] wav = WavBuilder.buildWav(pcm);

        ByteBuffer bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(36 + pcm.length, bb.getInt(4));   // RIFF chunk size
        assertEquals(pcm.length, bb.getInt(40));       // data chunk size
    }

    @Test
    void pcmFormatFieldsAreCorrect() {
        byte[] wav = WavBuilder.buildWav(new byte[1600]);

        ByteBuffer bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(16, bb.getInt(16));              // fmt chunk size
        assertEquals(1, bb.getShort(20));             // PCM
        assertEquals(1, bb.getShort(22));             // channels = mono
        assertEquals(16000, bb.getInt(24));           // sample rate
        assertEquals(16000 * 2, bb.getInt(28));       // byte rate (16k * 2 bytes)
        assertEquals(2, bb.getShort(32));             // block align
        assertEquals(16, bb.getShort(34));            // bits per sample
    }

    @Test
    void pcmDataPreservedAfterHeader() {
        byte[] pcm = new byte[1000];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i % 256);
        }
        byte[] wav = WavBuilder.buildWav(pcm);

        assertArrayEquals(pcm, Arrays.copyOfRange(wav, 44, wav.length));
    }

    @Test
    void emptyPcmStillProducesValidHeader() {
        byte[] wav = WavBuilder.buildWav(new byte[0]);
        assertEquals(44, wav.length);
        assertEquals("data", new String(wav, 36, 4));
    }
}
