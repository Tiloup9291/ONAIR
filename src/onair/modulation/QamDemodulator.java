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

import onair.crypto.EncryptionSettings;
import onair.protocol.OnAirProtocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Coherent QAM demodulator, designed as a streaming "modem" (connectionless mode).
 *
 * Receive chain:
 *  1. Accumulate incoming samples into a sliding buffer (bursts arrive
 *     fragmented as audio blocks of a few tens of ms).
 *  2. Squelch: only attempt decoding if the energy exceeds a floor
 *     (and, in addition, {@link CarrierDetector} confirms the presence of a signal).
 *  3. I/Q down-conversion (carrier) + anti-image low-pass + RRC matched filter.
 *  4. Data-aided acquisition: correlation of the "SYNC!" preamble -> frame
 *     position + gain/phase estimate (a complex channel factor).
 *  5. Gain/phase pre-correction, then tracking:
 *       - {@link GardnerTimingRecovery}: symbol clock (timing + drift),
 *       - {@link CostasLoop}: residual carrier phase/frequency (CFO).
 *  6. Decision (slicer) -> bits -> bytes -> {@link OnAirProtocol} (which uses the
 *     frame [length] field to delimit it).
 *
 * The protocol and the modulator are not modified.
 */
public class QamDemodulator {

    private static final boolean DEBUG = false;

    /** Energy floor (RMS) below which we consider there is no signal. */
    private static final double SQUELCH_RMS = 0.008;


    /** Real-time diagnostics (captured level + best preamble correlation). */
    public interface DiagnosticsListener {
        void onDiagnostics(double rms, double bestCorrelation);
    }

    /** Fired exactly ONCE when a detected burst ends without yielding a frame. */
    public interface BurstListener {
        void onBurstUndecoded(double correlation);
    }

    private DiagnosticsListener diagnosticsListener;
    private BurstListener burstListener;
    private volatile double lastCorrelation = Double.NaN;

    // True while a burst (carrier present) is being accumulated and not yet
    // resolved (decoded or evicted). Drives the receive state machine.
    private boolean collecting = false;

    public void setDiagnosticsListener(DiagnosticsListener listener) {
        this.diagnosticsListener = listener;
    }

    public void setBurstListener(BurstListener listener) {
        this.burstListener = listener;
    }

    /** Last preamble correlation score (NaN if no signal). */
    public double getLastCorrelation() {
        return lastCorrelation;
    }



    private final ModulationConfig config;
    private final QamConstellation constellation;
    private final double[] filterTaps;
    private final double[] lpfTaps;

    // Sliding buffer of samples accumulated between calls (streaming receiver).
    private double[] sampleBuffer = new double[0];
    private final int maxBufferSamples;

    public QamDemodulator(ModulationConfig config) {
        this.config = config.copy();
        this.constellation = new QamConstellation(config.getQamOrder());
        this.filterTaps = PulseShapingFilter.createTaps(config);

        double carrier = config.getCarrierFrequencyHz();
        double cutoffHz = Math.min(config.getSymbolsPerSecond() * (1.0 + config.getFilterRollOff()),
                carrier * 0.9);
        this.lpfTaps = lowPassTaps(cutoffHz, config.getSampleRateHz(), 4 * config.samplesPerSymbol());

        // Memory/CPU bound: ~6 s of signal is more than enough for one burst.
        // (In real-time streaming, keeps the per-block processing cost stable.)
        this.maxBufferSamples = config.getSampleRateHz() * 6;

    }

    public synchronized List<OnAirProtocol.OnAirFrame> processAudio(float[] samples, EncryptionSettings encryption) {
        List<OnAirProtocol.OnAirFrame> frames = new ArrayList<>();
        if (samples == null || samples.length == 0) {
            return frames;
        }

        appendSamples(samples);

        // Activity is evaluated on the MOST RECENT window only (not the whole
        // growing buffer). This is the key: with a continuous background noise
        // the global RMS never drops, so we MUST rely on the carrier presence in
        // the latest window to detect the START and, above all, the END of a
        // burst. Without this, a burst that fails to decode would stay forever
        // in the buffer and be re-correlated every block (frozen correlation +
        // endless "preamble detected but decoding failed" warnings).
        int sps = config.samplesPerSymbol();
        int winLen = Math.min(sampleBuffer.length, Math.max(samples.length, 2048));
        float[] recent = recentWindow(winLen);
        double recentRms = rms(recent);
        boolean recentCarrier = CarrierDetector.detectCarrier(
                recent, config.getSampleRateHz(), config.getCarrierFrequencyHz(), 1e-6);
        double wholeRms = rms(sampleBuffer);

        // Start collecting a burst as soon as there is signal activity.
        if (recentCarrier || recentRms >= SQUELCH_RMS) {
            collecting = true;
        }

        // -- IDLE: nothing in progress -> stay light, never warn. --
        if (!collecting) {
            lastCorrelation = Double.NaN;
            notifyDiagnostics(wholeRms, Double.NaN);
            trimToTail(sps * 4);
            return frames;
        }

        // -- COLLECTING: try to decode the accumulated burst. --
        frames = demodulateAndDecode(sampleBuffer, encryption);
        notifyDiagnostics(wholeRms, lastCorrelation);

        if (!frames.isEmpty()) {
            // Burst decoded: start fresh (connectionless = one burst per message).
            resetBurst();
            return frames;
        }

        // No frame this round.
        if (!recentCarrier) {
            // The carrier is gone: the burst (if any) is OVER. If a preamble was
            // locked but produced no frame, this is a genuine decode failure ->
            // notify ONCE, then EVICT the burst so it is never re-correlated
            // again. This removes BOTH the frozen-correlation and the repeated
            // "decoding failed" warning that the user observed.
            double corr = lastCorrelation;
            double threshold = config.getCorrelationThreshold();
            if (!Double.isNaN(corr) && corr >= threshold && corr >= 0.5) {
                BurstListener listener = burstListener;
                if (listener != null) {
                    listener.onBurstUndecoded(corr);
                }
            }
            resetBurst();
            return frames;
        }

        // Burst still in progress (carrier present): keep accumulating, but bound
        // the buffer so a stuck/garbage carrier can never grow without limit.
        if (sampleBuffer.length > maxBufferSamples) {
            resetBurst();
        }
        return frames;
    }

    /**
     * Demodulates a complete block and tries to decode ONAIR frames from it.
     * Also used by the tests (loopback / realistic channel).
     */
    public List<OnAirProtocol.OnAirFrame> demodulateAndDecode(double[] signal, EncryptionSettings encryption) {
        List<OnAirProtocol.OnAirFrame> frames = new ArrayList<>();
        int samplesPerSymbol = config.samplesPerSymbol();
        double sampleRate = config.getSampleRateHz();
        double carrierFreq = config.getCarrierFrequencyHz();

        if (signal.length < (filterTaps.length + 4 * samplesPerSymbol)) {
            return frames;
        }

        // -- 1. I/Q down-conversion to baseband --
        double[] inBb = new double[signal.length];
        double[] quBb = new double[signal.length];
        for (int n = 0; n < signal.length; n++) {
            double ph = 2.0 * Math.PI * carrierFreq * n / sampleRate;
            inBb[n] = 2.0 * signal[n] * Math.cos(ph);
            quBb[n] = -2.0 * signal[n] * Math.sin(ph);
        }

        // -- 1b. Anti-image low-pass (removes the component at 2*fc) --
        inBb = PulseShapingFilter.applyFIR(inBb, lpfTaps);
        quBb = PulseShapingFilter.applyFIR(quBb, lpfTaps);

        // -- 2. RRC matched filter --
        double[] inM = PulseShapingFilter.convolve(inBb, filterTaps);
        double[] quM = PulseShapingFilter.convolve(quBb, filterTaps);
        int length = Math.min(inM.length, quM.length);

        // Trailing guard: the matched-filter output near the very end of the
        // buffer lacks full support (those samples have not all "arrived" yet
        // in streaming), so symbols sampled there are unreliable and corrupt
        // the last bytes of a frame. We therefore IGNORE the last `guard`
        // samples: a frame whose tail falls in that zone is simply decoded one
        // block later, once enough trailing samples are buffered. This is the
        // core fix for "the last characters of the first message are garbled".
        int guard = filterTaps.length + lpfTaps.length + 2 * samplesPerSymbol;
        length = Math.max(0, length - guard);
        if (length <= 0) {
            return frames;
        }

        // -- Expected preamble symbols --
        double[] preIn;
        double[] preQu;
        {
            boolean[] preBits = QamModulator.bytesToBits(QamModulator.PREAMBLE);
            int bps = constellation.getBitsPerSymbol();
            int preSym = preBits.length / bps;
            preIn = new double[preSym];
            preQu = new double[preSym];
            for (int i = 0; i < preSym; i++) {
                int dataValue = 0;
                for (int b = 0; b < bps; b++) {
                    int bitIndex = i * bps + b;
                    if (bitIndex < preBits.length && preBits[bitIndex]) {
                        dataValue |= 1 << (bps - 1 - b);
                    }
                }
                int symbolIndex = constellation.symbolIndexFromBits(dataValue);
                preIn[i] = constellation.getIn(symbolIndex);
                preQu[i] = constellation.getQu(symbolIndex);
            }
        }
        int preCount = preIn.length;
        if (preCount < 1 || length < (preCount - 1) * samplesPerSymbol + 1) {
            return frames;
        }
        double sumE2 = 0.0;
        for (int i = 0; i < preCount; i++) {
            sumE2 += preIn[i] * preIn[i] + preQu[i] * preQu[i];
        }
        if (sumE2 <= 0) {
            return frames;
        }

        // -- 3. Acquisition: sliding correlation of the preamble --
        int lastStart = length - 1 - (preCount - 1) * samplesPerSymbol;
        int bestStart = -1;
        double bestScore = -1.0;
        double bestAccRe = 1.0, bestAccIm = 0.0;
        for (int start = 1; start <= lastStart; start++) {
            double accRe = 0.0, accIm = 0.0, sumR2 = 0.0;
            for (int k = 0; k < preCount; k++) {
                int idx = start + k * samplesPerSymbol;
                double rIn = inM[idx], rQu = quM[idx];
                double eIn = preIn[k], eQu = preQu[k];
                accRe += rIn * eIn + rQu * eQu;
                accIm += rQu * eIn - rIn * eQu;
                sumR2 += rIn * rIn + rQu * rQu;
            }
            if (sumR2 <= 0) continue;
            double score = (accRe * accRe + accIm * accIm) / (sumE2 * sumR2);
            if (score > bestScore) {
                bestScore = score;
                bestStart = start;
                bestAccRe = accRe;
                bestAccIm = accIm;
            }
        }
        // Store the best score for real-time diagnostics (even if below threshold).
        lastCorrelation = bestScore;
        // Threshold adjustable from the UI (0% to 95%). Lower = attempt decoding
        // even on weak correlation (frame structure consistency is the final guard).
        if (bestStart < 1 || bestScore < config.getCorrelationThreshold()) {
            return frames;
        }


        if (DEBUG) {
            System.out.printf("[RX] preamble @ %d  score=%.3f%n", bestStart, bestScore);
        }

        // Channel factor estimated on the preamble: factor = acc / sumE2 = gain*e^{jphi}.
        double factorRe = bestAccRe / sumE2;
        double factorIm = bestAccIm / sumE2;
        double factorMag2 = factorRe * factorRe + factorIm * factorIm;
        if (factorMag2 <= 0) {
            return frames;
        }

        // -- 4. Gain/phase pre-correction: nBb = r * conj(factor) / |factor|^2 --
        double[] nIn = new double[length];
        double[] nQu = new double[length];
        for (int n = 0; n < length; n++) {
            double rIn = inM[n], rQu = quM[n];
            nIn[n] = (rIn * factorRe + rQu * factorIm) / factorMag2;
            nQu[n] = (rQu * factorRe - rIn * factorIm) / factorMag2;
        }

        // -- 5a. Symbol clock recovery (Gardner) --
        GardnerTimingRecovery gardner =
                new GardnerTimingRecovery(samplesPerSymbol, 0.01, 0.707);
        GardnerTimingRecovery.Symbols sym = gardner.process(nIn, nQu, bestStart);
        if (sym.count < preCount) {
            return frames;
        }

        // -- 5b. Carrier tracking (Costas) + decision --
        CostasLoop costas = new CostasLoop(0.02, 0.707, constellation);
        int bitsPerSymbol = constellation.getBitsPerSymbol();
        boolean[] bits = new boolean[sym.count * bitsPerSymbol];
        int bitPos = 0;
        for (int i = 0; i < sym.count; i++) {
            double[] corr = costas.process(sym.in[i], sym.qu[i]);
            int symbolIndex = constellation.nearestSymbolIndex(corr[0], corr[1]);
            int dataValue = constellation.bitsFromSymbolIndex(symbolIndex);
            for (int b = 0; b < bitsPerSymbol; b++) {
                bits[bitPos++] = ((dataValue >> (bitsPerSymbol - 1 - b)) & 1) == 1;
            }
        }

        // -- 6. Bits -> bytes -> ONAIR protocol decoding --
        byte[] bytes = bitsToBytes(bits);
        int searchOffset = 0;
        while (searchOffset < bytes.length) {
            Optional<OnAirProtocol.OnAirFrame> decoded =
                    OnAirProtocol.tryDecode(bytes, searchOffset, bytes.length - searchOffset, encryption);
            if (decoded.isPresent()) {
                OnAirProtocol.OnAirFrame frame = decoded.get();
                frames.add(frame);
                searchOffset = Math.max(frame.getEndIndex(), searchOffset + 1);
            } else {
                break;
            }
        }
        return frames;
    }

    public synchronized void reset() {
        sampleBuffer = new double[0];
        lastCorrelation = Double.NaN;
        collecting = false;
    }

    /** Discards the current burst and returns to IDLE. */
    private void resetBurst() {
        sampleBuffer = new double[0];
        collecting = false;
        lastCorrelation = Double.NaN;
    }

    /** Returns a copy of the last {@code len} samples of the buffer (as float). */
    private float[] recentWindow(int len) {
        int n = Math.max(0, Math.min(len, sampleBuffer.length));
        float[] out = new float[n];
        int start = sampleBuffer.length - n;
        for (int i = 0; i < n; i++) {
            out[i] = (float) sampleBuffer[start + i];
        }
        return out;
    }

    private void notifyDiagnostics(double rms, double bestCorrelation) {
        DiagnosticsListener listener = diagnosticsListener;
        if (listener != null) {
            listener.onDiagnostics(rms, bestCorrelation);
        }
    }


    // ----------------------- Streaming helpers -----------------------

    private void appendSamples(float[] samples) {
        double[] merged = new double[sampleBuffer.length + samples.length];
        System.arraycopy(sampleBuffer, 0, merged, 0, sampleBuffer.length);
        for (int i = 0; i < samples.length; i++) {
            merged[sampleBuffer.length + i] = samples[i];
        }
        sampleBuffer = merged;
    }

    private void trimToTail(int tailLength) {
        if (tailLength <= 0) {
            sampleBuffer = new double[0];
            return;
        }
        if (sampleBuffer.length <= tailLength) {
            return;
        }
        double[] tail = new double[tailLength];
        System.arraycopy(sampleBuffer, sampleBuffer.length - tailLength, tail, 0, tailLength);
        sampleBuffer = tail;
    }

    private static double rms(double[] x) {
        if (x.length == 0) return 0.0;
        double s = 0.0;
        for (double v : x) s += v * v;
        return Math.sqrt(s / x.length);
    }

    private static double rms(float[] x) {
        if (x.length == 0) return 0.0;
        double s = 0.0;
        for (float v : x) s += (double) v * v;
        return Math.sqrt(s / x.length);
    }

    private static float[] toFloat(double[] x) {
        float[] f = new float[x.length];
        for (int i = 0; i < x.length; i++) f[i] = (float) x[i];
        return f;
    }

    // ----------------------- Low-pass filter -----------------------

    /** FIR low-pass filter (Hamming-windowed sinc), unity DC gain. */
    private static double[] lowPassTaps(double cutoffHz, double sampleRate, int numTaps) {
        if (numTaps % 2 == 0) {
            numTaps++;
        }
        double[] taps = new double[numTaps];
        int center = numTaps / 2;
        double fc = cutoffHz / sampleRate;
        double sum = 0.0;
        for (int i = 0; i < numTaps; i++) {
            int k = i - center;
            double sinc = (k == 0) ? 2.0 * fc : Math.sin(2.0 * Math.PI * fc * k) / (Math.PI * k);
            double window = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (numTaps - 1));
            taps[i] = sinc * window;
            sum += taps[i];
        }
        for (int i = 0; i < numTaps; i++) {
            taps[i] /= sum;
        }
        return taps;
    }

    private static byte[] bitsToBytes(boolean[] bits) {
        int byteCount = bits.length / 8;
        byte[] data = new byte[byteCount];
        for (int i = 0; i < byteCount * 8; i++) {
            if (bits[i]) {
                data[i / 8] |= (byte) (1 << (7 - (i % 8)));
            }
        }
        return data;
    }
}
