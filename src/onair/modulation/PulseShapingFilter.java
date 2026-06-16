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
 * Root raised cosine (RRC) filter for symbol pulse shaping.
 */

public final class PulseShapingFilter {

    private PulseShapingFilter() {
    }

    public static double[] createTaps(ModulationConfig config) {
        int samplesPerSymbol = config.samplesPerSymbol();
        int span = config.getFilterSpanSymbols();
        int tapCount = span * samplesPerSymbol + 1;
        double[] taps = new double[tapCount];
        double alpha = config.getFilterRollOff();
        double symbolPeriod = 1.0 / config.getSymbolsPerSecond();
        int center = tapCount / 2;

        for (int i = 0; i < tapCount; i++) {
            double t = (i - center) / (double) config.getSampleRateHz();

            taps[i] = rootRaisedCosine(t, symbolPeriod, alpha);
        }

        normalize(taps);
        return taps;
    }

    public static double[] convolve(double[] signal, double[] taps) {
        double[] output = new double[signal.length + taps.length - 1];
        for (int n = 0; n < output.length; n++) {
            double sum = 0.0;
            for (int k = 0; k < taps.length; k++) {
                int index = n - k;
                if (index >= 0 && index < signal.length) {
                    sum += signal[index] * taps[k];
                }
            }
            output[n] = sum;
        }
        return output;
    }

    public static double[] applyFIR(double[] signal, double[] taps) {
        double[] output = new double[signal.length];
        int half = taps.length / 2;
        for (int n = 0; n < signal.length; n++) {
            double sum = 0.0;
            for (int k = 0; k < taps.length; k++) {
                int index = n - k + half;
                if (index >= 0 && index < signal.length) {
                    sum += signal[index] * taps[k];
                }
            }
            output[n] = sum;
        }
        return output;
    }

    private static double rootRaisedCosine(double t, double symbolPeriod, double alpha) {
        double tAbs = Math.abs(t);
        double tNorm = tAbs / symbolPeriod;
        if (tNorm < 1e-12) {
            return (1.0 - alpha + 4.0 * alpha / Math.PI) / symbolPeriod;
        }

        if (alpha > 0.0 && Math.abs(tAbs - symbolPeriod / (4.0 * alpha)) < 1e-9) {
            double term = alpha / Math.sqrt(2.0);
            return (term * ((1.0 + 2.0 / Math.PI) * Math.sin(Math.PI / (4.0 * alpha))
                    + (1.0 - 2.0 / Math.PI) * Math.cos(Math.PI / (4.0 * alpha)))) / symbolPeriod;
        }

        double numerator = Math.sin(Math.PI * tNorm * (1.0 - alpha))
                + 4.0 * alpha * tNorm * Math.cos(Math.PI * tNorm * (1.0 + alpha));
        double denominator = Math.PI * tNorm * (1.0 - Math.pow(4.0 * alpha * tNorm, 2.0));
        if (Math.abs(denominator) < 1e-12) {
            return 0.0;
        }
        return numerator / denominator / symbolPeriod;
    }

    private static void normalize(double[] taps) {
        /*double sum = 0.0;
        for (double tap : taps) {
            sum += tap;
        }
        if (Math.abs(sum) > 1e-12) {
            for (int i = 0; i < taps.length; i++) {
                taps[i] /= sum;
            }
        }*/
	double energy = 0.0;
	for (double tap : taps) {
    		energy += tap * tap;
	}
	double norm = Math.sqrt(energy);

	for (int i = 0; i < taps.length; i++) {
    		taps[i] /= norm;
	}
    }
}
