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
 * Symbol clock recovery using the Gardner algorithm (non data-aided).
 *
 * Complex (I/Q) version with a fractional interpolator: operates on the baseband
 * signal after the matched filter, sampled at {@code samplesPerSymbol} samples
 * per symbol. The Gardner error detector uses a midpoint located at T/2 between
 * two symbol instants -- hence the need for a true interpolator (not consecutive
 * samples).
 *
 * Gardner detector (per component, summed over I and Q):
 *   e = Re{ (x[k] - x[k-1]) * conj(x[k-1/2]) }
 *     = I_mid*(I_k - I_{k-1}) + Q_mid*(Q_k - Q_{k-1})
 *
 * 2nd-order PI loop driving the effective symbol period (clock drift tracking),
 * with cubic Lagrange interpolation (4 points).
 */
public class GardnerTimingRecovery {

    private final int samplesPerSymbol;
    private final double alpha;   // proportional gain
    private final double beta;    // integrator gain
    private final double maxDrift; // max period drift (in samples)

    /** Result: I/Q symbols sampled at the optimal instants. */
    public static final class Symbols {
        public final double[] in;
        public final double[] qu;
        public final int count;

        Symbols(double[] in, double[] qu, int count) {
            this.in = in;
            this.qu = qu;
            this.count = count;
        }
    }

    /**
     * @param samplesPerSymbol nominal number of samples per symbol
     * @param loopBandwidth    normalized bandwidth (typically 0.01 to 0.05)
     * @param dampingFactor    damping factor (typically 0.707)
     */
    public GardnerTimingRecovery(int samplesPerSymbol, double loopBandwidth, double dampingFactor) {
        this.samplesPerSymbol = samplesPerSymbol;
        double denom = 1.0 + 2.0 * dampingFactor * loopBandwidth + loopBandwidth * loopBandwidth;
        this.alpha = (4.0 * dampingFactor * loopBandwidth) / denom;
        this.beta = (4.0 * loopBandwidth * loopBandwidth) / denom;
        this.maxDrift = 0.05 * samplesPerSymbol; // +/-5% clock drift
    }

    /**
     * Extracts the synchronized symbols from the baseband I/Q stream.
     *
     * @param bbIn    baseband I component (sample rate)
     * @param bbQu    baseband Q component
     * @param startPos (fractional) position of the FIRST symbol, provided by the
     *                 data-aided acquisition (preamble correlation)
     * @return the recovered I/Q symbols
     */
    public Symbols process(double[] bbIn, double[] bbQu, double startPos) {
        int len = Math.min(bbIn.length, bbQu.length);
        int capacity = Math.max(1, (int) (len / (double) samplesPerSymbol) + 4);
        double[] outI = new double[capacity];
        double[] outQ = new double[capacity];
        int n = 0;

        double period = samplesPerSymbol;   // effective symbol period
        double integ = 0.0;                  // loop integrator (drift)
        double t = startPos;                 // current symbol instant (fractional)
        double prevI = 0.0, prevQ = 0.0;
        boolean havePrev = false;

        // Bounds: we need t-1 .. t+2 (cubic interp) and the midpoint t-period/2.
        while (true) {
            double mid = t - period / 2.0;
            if (!canInterp(mid, len) || !canInterp(t, len)) {
                break;
            }

            double curI = interp(bbIn, t);
            double curQ = interp(bbQu, t);

            if (n >= outI.length) {
                double[] gi = new double[outI.length * 2];
                double[] gq = new double[outQ.length * 2];
                System.arraycopy(outI, 0, gi, 0, n);
                System.arraycopy(outQ, 0, gq, 0, n);
                outI = gi;
                outQ = gq;
            }
            outI[n] = curI;
            outQ[n] = curQ;
            n++;

            double step = period;
            if (havePrev) {
                double midI = interp(bbIn, mid);
                double midQ = interp(bbQu, mid);

                // Gardner error (I and Q), normalized by the local power.
                double e = midI * (curI - prevI) + midQ * (curQ - prevQ);
                double power = curI * curI + curQ * curQ + prevI * prevI + prevQ * prevQ + 1e-9;
                e /= power;

                integ += beta * e;
                if (integ > maxDrift) integ = maxDrift;
                if (integ < -maxDrift) integ = -maxDrift;

                step = period + alpha * e + integ;
            }

            prevI = curI;
            prevQ = curQ;
            havePrev = true;

            t += step;
        }

        return new Symbols(outI, outQ, n);
    }

    private boolean canInterp(double pos, int len) {
        int base = (int) Math.floor(pos);
        return base - 1 >= 0 && base + 2 < len;
    }

    /** Cubic Lagrange interpolation (4 points) at the fractional position pos. */
    private double interp(double[] x, double pos) {
        int base = (int) Math.floor(pos);
        double mu = pos - base;
        double ym1 = x[base - 1];
        double y0 = x[base];
        double y1 = x[base + 1];
        double y2 = x[base + 2];

        // 4-point Lagrange (cubic) form.
        double c0 = y0;
        double c1 = y1 - (1.0 / 3.0) * ym1 - 0.5 * y0 - (1.0 / 6.0) * y2;
        double c2 = 0.5 * (ym1 + y1) - y0;
        double c3 = (1.0 / 6.0) * (y2 - ym1) + 0.5 * (y0 - y1);
        return ((c3 * mu + c2) * mu + c1) * mu + c0;
    }
}
