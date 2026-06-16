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
package onair;

import onair.modulation.ModulationConfig;
import onair.modulation.PulseShapingFilter;
import onair.modulation.QamConstellation;

/**
 * Low-level probe: transmits known CONSTANT symbols through
 * TX(RRC+upconvert) -> RX(downconvert+RRC) and prints the recovered I/Q at the
 * sampling instants. Helps isolate a potential fault in the DSP chain.
 */
public final class ChainProbe {
    public static void main(String[] args) {
        ModulationConfig config = new ModulationConfig();
        config.setBitsPerSymbol(4); // QAM-16
        QamConstellation c = new QamConstellation(config.getQamOrder());
        double[] taps = PulseShapingFilter.createTaps(config);
        int sps = config.samplesPerSymbol();
        double fs = config.getSampleRateHz();
        double fc = config.getCarrierFrequencyHz();

        // 20 identical symbols: we take the point at index 3
        int symIndex = 3;
        double si = c.getIn(symIndex), sq = c.getQu(symIndex);
        int nSym = 20;

        double[] inUp = new double[nSym * sps];
        double[] quUp = new double[nSym * sps];
        for (int i = 0; i < nSym; i++) {
            inUp[i * sps] = si;
            quUp[i * sps] = sq;
        }

        double[] inSh = PulseShapingFilter.convolve(inUp, taps);
        double[] quSh = PulseShapingFilter.convolve(quUp, taps);
        int len = Math.min(inSh.length, quSh.length);

        double[] pass = new double[len];
        for (int n = 0; n < len; n++) {
            double ph = 2.0 * Math.PI * fc * n / fs;
            pass[n] = inSh[n] * Math.cos(ph) - quSh[n] * Math.sin(ph);
        }

        // RX
        double[] inBb = new double[len];
        double[] quBb = new double[len];
        for (int n = 0; n < len; n++) {
            double ph = 2.0 * Math.PI * fc * n / fs;
            inBb[n] = 2.0 * pass[n] * Math.cos(ph);
            quBb[n] = -2.0 * pass[n] * Math.sin(ph);
        }
        double[] inM = PulseShapingFilter.convolve(inBb, taps);
        double[] quM = PulseShapingFilter.convolve(quBb, taps);

        // Nyquist criterion check: the RRC*RRC composite must have zeros at
        // multiples of SPS around its center.
        System.out.println("--- Some RRC coefficients (taps) ---");
        int[] probe = {0, 100, 200, 300, 350, 390, 399, 400, 401, 410, 450, 500, 600, 700, 800};
        for (int idx : probe) {
            System.out.printf("  taps[%3d] = %12.6f%n", idx, taps[idx]);
        }

        double[] comp = PulseShapingFilter.convolve(taps, taps);

        int compCenter = (comp.length - 1) / 2;
        System.out.println("--- RRC*RRC composite (should be 0 at +/-k*SPS) ---");
        for (int m = -3; m <= 3; m++) {
            int idx = compCenter + m * sps;
            System.out.printf("  lag %+4d*SPS : %10.5f%n", m, comp[idx]);
        }
        System.out.printf("  sum(taps)=%.5f  sum(taps^2)=%.5f%n",
                sum(taps), sumSq(taps));
        System.out.println("----------------------------------------------------");

        int delay = taps.length - 1; // 2 * center

        System.out.printf("Expected constant symbol: I=%.3f Q=%.3f (delay=%d)%n", si, sq, delay);
        for (int k = 0; k < nSym; k++) {
            int idx = delay + k * sps;
            if (idx >= inM.length) break;
            System.out.printf("k=%2d  I=%8.4f  Q=%8.4f%n", k, inM[idx], quM[idx]);
        }
    }

    private static double sum(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return s;
    }

    private static double sumSq(double[] a) {
        double s = 0;
        for (double v : a) s += v * v;
        return s;
    }
}


