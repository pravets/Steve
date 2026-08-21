package ru.pravets.vasyan.voice;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WAV file assembly for recorded PCM audio (16 kHz / 16-bit / mono, signed,
 * little-endian). Pure helper - unit-testable without Minecraft.
 */
public final class WavBuilder {

    public static final int SAMPLE_RATE = 16000;
    public static final int BITS_PER_SAMPLE = 16;
    public static final int CHANNELS = 1;

    private WavBuilder() {}

    /** Wraps raw PCM bytes into a WAV container (44-byte canonical header). */
    public static byte[] buildWav(byte[] pcm) {
        int dataSize = pcm.length;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        header.putInt(36 + dataSize);
        header.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        header.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        header.putInt(16);                      // fmt chunk size
        header.putShort((short) 1);             // PCM
        header.putShort((short) CHANNELS);
        header.putInt(SAMPLE_RATE);
        header.putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8);
        header.putShort((short) (CHANNELS * BITS_PER_SAMPLE / 8));
        header.putShort((short) BITS_PER_SAMPLE);
        header.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        header.putInt(dataSize);

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);
        out.writeBytes(header.array());
        out.writeBytes(pcm);
        return out.toByteArray();
    }
}
