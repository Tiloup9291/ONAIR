/*
 ONAIR - QAM Messenger
 Copyright (C) 2026  John Doe

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, version 3 of the License, GPL-3.0-only.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <https://www.gnu.org/licenses/>
*/
package onair.audio;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

/**
 * Utility to read and write WAV files.
 */
public class WavFile {

    /**
     * Saves audio samples to a WAV file.
     *
     * @param samples    Audio samples (float -1.0 to 1.0)
     * @param sampleRate Sample rate in Hz
     * @param file       Destination file
     */
    public static void write(float[] samples, int sampleRate, File file) throws IOException {
        // Convert floats to 16-bit PCM
        byte[] pcmData = floatsToPcm16(samples);

        // Create the audio format
        AudioFormat format = new AudioFormat(
                sampleRate,
                16,      // bits per sample
                1,       // mono
                true,    // signed
                false    // little-endian
        );

        // Create an audio stream
        ByteArrayInputStream byteStream = new ByteArrayInputStream(pcmData);
        AudioInputStream audioStream = new AudioInputStream(
                byteStream,
                format,
                samples.length
        );

        // Write the WAV file
        AudioSystem.write(audioStream, AudioFileFormat.Type.WAVE, file);
        audioStream.close();
    }

    /**
     * Reads audio samples from a WAV file.
     *
     * @param file WAV file to read
     * @return Audio samples (float -1.0 to 1.0)
     */
    public static float[] read(File file) throws IOException {
        try {
            AudioInputStream audioStream = AudioSystem.getAudioInputStream(file);
            AudioFormat format = audioStream.getFormat();

            // Read all the data
            byte[] data = audioStream.readAllBytes();
            audioStream.close();

            // Convert to floats depending on the format
            if (format.getSampleSizeInBits() == 16) {
                return pcm16ToFloats(data);
            } else if (format.getSampleSizeInBits() == 8) {
                return pcm8ToFloats(data);
            } else {
                throw new IOException("Unsupported format: " + format.getSampleSizeInBits() + " bits");
            }
        } catch (Exception e) {
            throw new IOException("WAV file read error: " + e.getMessage(), e);
        }
    }

    /**
     * Converts floats to 16-bit PCM.
     */
    private static byte[] floatsToPcm16(float[] samples) {
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            float clamped = Math.max(-1.0f, Math.min(1.0f, samples[i]));
            short value = (short) (clamped * 32767.0f);
            pcm[i * 2] = (byte) (value & 0xFF);
            pcm[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return pcm;
    }

    /**
     * Converts 16-bit PCM to floats.
     */
    private static float[] pcm16ToFloats(byte[] pcmData) {
        float[] samples = new float[pcmData.length / 2];
        for (int i = 0; i < samples.length; i++) {
            int lo = pcmData[i * 2] & 0xFF;
            int hi = pcmData[i * 2 + 1];
            short sample = (short) ((hi << 8) | lo);
            samples[i] = sample / 32768.0f;
        }
        return samples;
    }

    /**
     * Converts 8-bit PCM to floats.
     */
    private static float[] pcm8ToFloats(byte[] pcmData) {
        float[] samples = new float[pcmData.length];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (pcmData[i] & 0xFF) / 128.0f - 1.0f;
        }
        return samples;
    }
}
