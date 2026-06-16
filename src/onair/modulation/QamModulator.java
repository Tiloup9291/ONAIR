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
package onair.modulation;

import java.nio.charset.StandardCharsets;

/**
 * Passband QAM modulator producing a mono audio signal.
 *
 * Correct transmit chain:
 * 1. Frame = PREAMBLE + payload
 * 2. Bytes -> bits -> QAM symbols (Gray mapping via the constellation)
 * 3. Impulse train (one sample per symbol, the rest set to zero)
 * 4. RRC pulse shaping (root raised cosine filter)
 * 5. Up-conversion to passband (I/Q carrier)
 */
public class QamModulator {

    static final byte[] PREAMBLE = "SYNC!".getBytes(StandardCharsets.US_ASCII);

    private final ModulationConfig config;
    private final QamConstellation constellation;
    private final double[] filterTaps;

    public QamModulator(ModulationConfig config) {
        this.config = config.copy();
        this.constellation = new QamConstellation(config.getQamOrder());
        this.filterTaps = PulseShapingFilter.createTaps(config);
    }

    public float[] modulate(byte[] payload) {
        byte[] frame = buildFrame(payload);
        boolean[] bits = bytesToBits(frame);
        int[] dataSymbols = bitsToSymbolIndices(bits);

        int samplesPerSymbol = config.samplesPerSymbol();

        // Guard (postamble) symbols appended AFTER the data so the last useful
        // symbols always have clean pulse-shaping neighbours instead of being
        // adjacent to the trailing silence/noise of the burst (which would
        // degrade their SNR at the receiver's matched filter and corrupt the
        // last bytes of the message). The receiver reads the frame by its
        // [length] field and simply ignores these trailing symbols.
        int[] guardSymbols = buildGuardSymbols(config.getFilterSpanSymbols() + 2);
        int[] symbolIndices = new int[dataSymbols.length + guardSymbols.length];
        System.arraycopy(dataSymbols, 0, symbolIndices, 0, dataSymbols.length);
        System.arraycopy(guardSymbols, 0, symbolIndices, dataSymbols.length, guardSymbols.length);

        // Impulse train: one symbol value every samplesPerSymbol samples.
        double[] inUpsampled = new double[symbolIndices.length * samplesPerSymbol];
        double[] quUpsampled = new double[symbolIndices.length * samplesPerSymbol];
        for (int i = 0; i < symbolIndices.length; i++) {
            int symbolIndex = symbolIndices[i];
            inUpsampled[i * samplesPerSymbol] = constellation.getIn(symbolIndex);
            quUpsampled[i * samplesPerSymbol] = constellation.getQu(symbolIndex);
        }

        // RRC pulse shaping.
        double[] inShaped = PulseShapingFilter.convolve(inUpsampled, filterTaps);
        double[] quShaped = PulseShapingFilter.convolve(quUpsampled, filterTaps);

        int length = Math.min(inShaped.length, quShaped.length);
        double[] passband = new double[length];
        double sampleRate = config.getSampleRateHz();
        double carrier = config.getCarrierFrequencyHz();

        for (int n = 0; n < length; n++) {
            double phase = 2.0 * Math.PI * carrier * n / sampleRate;
            passband[n] = inShaped[n] * Math.cos(phase) - quShaped[n] * Math.sin(phase);
        }

        // Normalize to avoid clipping the 16-bit PCM output.
        double max = 0.0;
        for (double v : passband) {
            double a = Math.abs(v);
            if (a > max) {
                max = a;
            }
        }
        double amplitude = 0.85;
        if (max > 0) {
            double scale = amplitude / max;
            for (int n = 0; n < length; n++) {
                passband[n] *= scale;
            }
        }

        return toFloatArray(passband);
    }

    private byte[] buildFrame(byte[] payload) {
        byte[] frame = new byte[PREAMBLE.length + payload.length];
        System.arraycopy(PREAMBLE, 0, frame, 0, PREAMBLE.length);
        System.arraycopy(payload, 0, frame, PREAMBLE.length, payload.length);
        return frame;
    }

    static boolean[] bytesToBits(byte[] data) {
        boolean[] bits = new boolean[data.length * 8];
        for (int i = 0; i < data.length; i++) {
            int value = data[i] & 0xFF;
            for (int b = 7; b >= 0; b--) {
                bits[i * 8 + (7 - b)] = ((value >> b) & 1) == 1;
            }
        }
        return bits;
    }

    /**
     * Converts bits into constellation symbol indices.
     * Gray mapping is entirely handled by the constellation (symbolIndexFromBits),
     * so NO additional Gray transformation must be applied here.
     */
    private int[] bitsToSymbolIndices(boolean[] bits) {
        int bitsPerSymbol = constellation.getBitsPerSymbol();
        int symbolCount = (int) Math.ceil(bits.length / (double) bitsPerSymbol);
        int[] symbols = new int[symbolCount];

        for (int i = 0; i < symbolCount; i++) {
            int dataValue = 0;
            for (int b = 0; b < bitsPerSymbol; b++) {
                int bitIndex = i * bitsPerSymbol + b;
                if (bitIndex < bits.length && bits[bitIndex]) {
                    dataValue |= 1 << (bitsPerSymbol - 1 - b);
                }
            }
            symbols[i] = constellation.symbolIndexFromBits(dataValue);
        }
        return symbols;
    }

    /**
     * Builds {@code count} guard symbols used as a postamble. They alternate
     * between two distinct constellation points so the signal keeps energy and
     * symbol transitions (helps the receiver's timing recovery stay locked
     * through the end of the burst). They carry no data.
     */
    private int[] buildGuardSymbols(int count) {
        int n = Math.max(0, count);
        int[] guard = new int[n];
        int maxBits = (1 << constellation.getBitsPerSymbol()) - 1;
        int a = constellation.symbolIndexFromBits(0);
        int b = constellation.symbolIndexFromBits(maxBits);
        for (int i = 0; i < n; i++) {
            guard[i] = (i % 2 == 0) ? a : b;
        }
        return guard;
    }

    private static float[] toFloatArray(double[] samples) {
        float[] output = new float[samples.length];
        for (int i = 0; i < samples.length; i++) {
            output[i] = (float) samples[i];
        }
        return output;
    }

    public int estimateDurationMs(byte[] payload) {
        byte[] frame = buildFrame(payload);
        int bitsPerSymbol = constellation.getBitsPerSymbol();
        int symbolCount = (int) Math.ceil(frame.length * 8.0 / bitsPerSymbol);
        int sampleCount = symbolCount * config.samplesPerSymbol() + filterTaps.length;
        return (int) Math.ceil(sampleCount * 1000.0 / config.getSampleRateHz());
    }
}
