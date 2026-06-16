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

/**
 * QAM modulation parameters configurable from the UI.
 */
public class ModulationConfig {

    /** Maximum number of bits per symbol (QAM-65536). */
    public static final int MAX_BITS_PER_SYMBOL = 16;
    public static final int MIN_BITS_PER_SYMBOL = 1;

    private int sampleRateHz = 44100;
    private double carrierFrequencyHz = 2000.0;
    private double symbolsPerSecond = 441.0;  // 44100/441 = 100 samples/symbol (exact integer!)
    private int qamOrder = 16;
    private double filterRollOff = 0.35;
    private int filterSpanSymbols = 8;
    private double correlationThreshold = 0.70;  // Preamble detection threshold (0%-95%)


    public int getSampleRateHz() {
        return sampleRateHz;
    }

    public void setSampleRateHz(int sampleRateHz) {
        if (sampleRateHz < 8000 || sampleRateHz > 192000) {
            throw new IllegalArgumentException("Invalid sample rate: " + sampleRateHz);
        }

        this.sampleRateHz = sampleRateHz;
    }

    public double getCarrierFrequencyHz() {
        return carrierFrequencyHz;
    }

    public void setCarrierFrequencyHz(double carrierFrequencyHz) {
        if (carrierFrequencyHz <= 0 || carrierFrequencyHz >= sampleRateHz / 2.0) {
            throw new IllegalArgumentException("Invalid carrier frequency: " + carrierFrequencyHz);
        }
        this.carrierFrequencyHz = carrierFrequencyHz;
    }

    public double getSymbolsPerSecond() {
        return symbolsPerSecond;
    }

    public void setSymbolsPerSecond(double symbolsPerSecond) {
        if (symbolsPerSecond <= 0 || symbolsPerSecond > sampleRateHz / 4.0) {
            throw new IllegalArgumentException("Invalid symbols/second: " + symbolsPerSecond);
        }
        this.symbolsPerSecond = symbolsPerSecond;
    }

    public int getQamOrder() {
        return qamOrder;
    }

    public void setQamOrder(int qamOrder) {
        if (!isPowerOfTwo(qamOrder)) {
            throw new IllegalArgumentException(
                    "QAM order must be a power of 2 (2, 4, 8, 16, 512, 1024, ...): " + qamOrder);
        }
        int bits = bitsPerSymbol(qamOrder);
        if (bits < MIN_BITS_PER_SYMBOL || bits > MAX_BITS_PER_SYMBOL) {
            throw new IllegalArgumentException(
                    "QAM order out of range (QAM-2 to QAM-" + orderFromBits(MAX_BITS_PER_SYMBOL) + "): " + qamOrder);
        }
        this.qamOrder = qamOrder;
    }

    public void setBitsPerSymbol(int bitsPerSymbol) {
        setQamOrder(orderFromBits(bitsPerSymbol));
    }

    public static boolean isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    public static int orderFromBits(int bitsPerSymbol) {
        if (bitsPerSymbol < MIN_BITS_PER_SYMBOL || bitsPerSymbol > MAX_BITS_PER_SYMBOL) {
            throw new IllegalArgumentException(
                    "Invalid bits per symbol (1-" + MAX_BITS_PER_SYMBOL + "): " + bitsPerSymbol);
        }
        return 1 << bitsPerSymbol;
    }

    public static int bitsPerSymbol(int qamOrder) {
        if (!isPowerOfTwo(qamOrder)) {
            throw new IllegalArgumentException("QAM order must be a power of 2: " + qamOrder);
        }
        return Integer.numberOfTrailingZeros(qamOrder);
    }

    public double getFilterRollOff() {
        return filterRollOff;
    }

    public void setFilterRollOff(double filterRollOff) {
        if (filterRollOff < 0.0 || filterRollOff > 1.0) {
            throw new IllegalArgumentException("Invalid roll-off: " + filterRollOff);
        }
        this.filterRollOff = filterRollOff;
    }

    public int getFilterSpanSymbols() {
        return filterSpanSymbols;
    }

    public void setFilterSpanSymbols(int filterSpanSymbols) {
        if (filterSpanSymbols < 2 || filterSpanSymbols > 32) {
            throw new IllegalArgumentException("Invalid filter span: " + filterSpanSymbols);
        }
        this.filterSpanSymbols = filterSpanSymbols;
    }

    public int samplesPerSymbol() {
        return Math.max(2, (int) Math.round(sampleRateHz / symbolsPerSecond));
    }

    public int bitsPerSymbol() {
        return bitsPerSymbol(qamOrder);
    }

    public double getCorrelationThreshold() {
        return correlationThreshold;
    }

    public void setCorrelationThreshold(double correlationThreshold) {
        if (correlationThreshold < 0.0 || correlationThreshold > 0.95) {
            throw new IllegalArgumentException("Invalid correlation threshold (0%-95%): " + correlationThreshold);
        }
        this.correlationThreshold = correlationThreshold;
    }


    public ModulationConfig copy() {
        ModulationConfig copy = new ModulationConfig();
        copy.sampleRateHz = sampleRateHz;
        copy.carrierFrequencyHz = carrierFrequencyHz;
        copy.symbolsPerSecond = symbolsPerSecond;
        copy.qamOrder = qamOrder;
        copy.filterRollOff = filterRollOff;
        copy.filterSpanSymbols = filterSpanSymbols;
        copy.correlationThreshold = correlationThreshold;
        return copy;
    }
}
