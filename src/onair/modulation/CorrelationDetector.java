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
 * Preamble detector by correlation.
 * Uses cross-correlation to detect the "SYNC!" sequence in the bit stream.
 * More robust than a plain search because it tolerates noise better.
 */
public class CorrelationDetector {
    
    private static final byte[] PREAMBLE = "SYNC!".getBytes(StandardCharsets.US_ASCII);
    private static final int PREAMBLE_BITS = PREAMBLE.length * 8;
    
    private final double threshold;  // Correlation threshold (0.0 to 1.0)
    private final boolean[] preambleBits;
    private final boolean[] buffer;
    private int bufferPos;
    
    /**
     * @param threshold Normalized correlation threshold (typically 0.7 to 0.95)
     */
    public CorrelationDetector(double threshold) {
        this.threshold = threshold;
        this.preambleBits = bytesToBits(PREAMBLE);
        this.buffer = new boolean[PREAMBLE_BITS];
        this.bufferPos = 0;
    }
    
    /**
     * Adds a bit to the buffer and checks the correlation.
     * @param bit The bit to add
     * @return true if the preamble is detected, false otherwise
     */
    public boolean addBit(boolean bit) {
        // Add the bit to the circular buffer
        buffer[bufferPos] = bit;
        bufferPos = (bufferPos + 1) % PREAMBLE_BITS;
        
        // Compute the correlation
        int matches = 0;
        for (int i = 0; i < PREAMBLE_BITS; i++) {
            int bufferIndex = (bufferPos + i) % PREAMBLE_BITS;
            if (buffer[bufferIndex] == preambleBits[i]) {
                matches++;
            }
        }
        
        // Normalized correlation
        double correlation = (double) matches / PREAMBLE_BITS;
        
        return correlation >= threshold;
    }
    
    /**
     * Searches for the preamble in a bit array.
     * @param bits Bit array to analyze
     * @return Position of the preamble, or -1 if not found
     */
    public int findPreamble(boolean[] bits) {
        if (bits.length < PREAMBLE_BITS) {
            return -1;
        }
        
        int bestPosition = -1;
        double bestCorrelation = 0.0;
        
        // Scan the whole array
        for (int start = 0; start <= bits.length - PREAMBLE_BITS; start++) {
            int matches = 0;
            for (int i = 0; i < PREAMBLE_BITS; i++) {
                if (bits[start + i] == preambleBits[i]) {
                    matches++;
                }
            }
            
            double correlation = (double) matches / PREAMBLE_BITS;
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                bestPosition = start;
            }
            
            // If the correlation is perfect, no need to continue
            if (correlation >= 0.99) {
                break;
            }
        }
        
        // LOG: Print the best score found (even if below the threshold)
        System.out.printf("[Correlation] Best score: %.2f%% @ position %d (threshold: %.2f%%)%n",
            bestCorrelation * 100, bestPosition, threshold * 100);
        
        // Return the position only if above the threshold
        if (bestCorrelation >= threshold) {
            System.out.println("[Correlation] SYNC! detected (above threshold)");
            return bestPosition;
        }
        
        System.out.println("[Correlation] SYNC! NOT detected (below threshold)");
        return -1;
    }
    
    /**
     * Searches for the preamble in a stream of QAM symbols.
     * First converts the symbols to bits, then looks for the preamble.
     * @param symbols Demodulated symbols
     * @param constellation QAM constellation to convert symbols -> bits
     * @return Position of the symbol where the preamble starts, or -1 if not found
     */
    public int findPreambleInSymbols(int[] symbols, QamConstellation constellation) {
        // Convert the symbols to bits
        int bitsPerSymbol = constellation.getBitsPerSymbol();
        boolean[] bits = new boolean[symbols.length * bitsPerSymbol];
        
        for (int i = 0; i < symbols.length; i++) {
            int symbolBits = constellation.bitsFromSymbolIndex(symbols[i]);
            for (int b = 0; b < bitsPerSymbol; b++) {
                bits[i * bitsPerSymbol + b] = ((symbolBits >> (bitsPerSymbol - 1 - b)) & 1) == 1;
            }
        }
        
        // Look for the preamble in the bits
        int bitPosition = findPreamble(bits);
        
        if (bitPosition >= 0) {
            // Convert the bit position into a symbol position
            return bitPosition / bitsPerSymbol;
        }
        
        return -1;
    }
    
    /**
     * Computes the correlation at a specific position.
     * Useful to check the detection quality.
     */
    public double getCorrelationAt(boolean[] bits, int position) {
        if (position < 0 || position + PREAMBLE_BITS > bits.length) {
            return 0.0;
        }
        
        int matches = 0;
        for (int i = 0; i < PREAMBLE_BITS; i++) {
            if (bits[position + i] == preambleBits[i]) {
                matches++;
            }
        }
        
        return (double) matches / PREAMBLE_BITS;
    }
    
    /**
     * Resets the detection buffer.
     */
    public void reset() {
        bufferPos = 0;
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = false;
        }
    }
    
    /**
     * Converts a byte array into a bit array.
     */
    private static boolean[] bytesToBits(byte[] bytes) {
        boolean[] bits = new boolean[bytes.length * 8];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            for (int b = 7; b >= 0; b--) {
                bits[i * 8 + (7 - b)] = ((value >> b) & 1) == 1;
            }
        }
        return bits;
    }
    
    public int getPreambleBitLength() {
        return PREAMBLE_BITS;
    }
    
    public double getThreshold() {
        return threshold;
    }
}
