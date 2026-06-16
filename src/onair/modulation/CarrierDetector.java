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
 * FFT-based carrier detector to check for the presence of a signal.
 */
public class CarrierDetector {

    private static final boolean DEBUG = false;


    /**
     * Detects whether a carrier is present in the signal at the specified frequency.
     *
     * @param samples       Audio samples
     * @param sampleRate    Sample rate (Hz)
     * @param carrierFreq   Expected carrier frequency (Hz)
     * @param threshold     Energy threshold (typically 0.01 to 0.1)
     * @return true if the carrier is detected
     */
    public static boolean detectCarrier(float[] samples, int sampleRate, double carrierFreq, double threshold) {
        if (samples == null || samples.length < 64) {
            if (DEBUG) System.out.println("[CarrierDetector] Insufficient samples: " + (samples == null ? "null" : samples.length));
            return false;
        }

        // Compute the max amplitude for diagnostics
        float maxAmplitude = 0;
        for (float sample : samples) {
            maxAmplitude = Math.max(maxAmplitude, Math.abs(sample));
        }
        
        if (DEBUG) {
            System.out.println("[CarrierDetector] Signal max amplitude: " + String.format("%.4f", maxAmplitude));
        }

        // Use a power-of-two window size for the FFT
        int fftSize = nextPowerOfTwo(Math.min(samples.length, 2048));
        double[] energy = computeEnergySpectrum(samples, fftSize);

        // Find the bin corresponding to the carrier frequency
        int carrierBin = (int) Math.round(carrierFreq * fftSize / sampleRate);
        if (carrierBin >= energy.length) {
            carrierBin = energy.length - 1;
        }

        // Measure the energy around the carrier (bin +/- 1 for tolerance)
        double carrierEnergy = 0.0;
        int binRange = 2;
        int count = 0;
        for (int i = Math.max(0, carrierBin - binRange); i <= Math.min(energy.length - 1, carrierBin + binRange); i++) {
            carrierEnergy += energy[i];
            count++;
        }
        carrierEnergy /= count;

        // Compute the average total energy
        double totalEnergy = 0.0;
        for (double e : energy) {
            totalEnergy += e;
        }
        totalEnergy /= energy.length;

        if (DEBUG) {
            System.out.println("[CarrierDetector] FFT size: " + fftSize);
            System.out.println("[CarrierDetector] Carrier bin: " + carrierBin + " (" + carrierFreq + " Hz)");
            System.out.println("[CarrierDetector] Carrier energy: " + String.format("%.6f", carrierEnergy));
            System.out.println("[CarrierDetector] Average energy: " + String.format("%.6f", totalEnergy));
            System.out.println("[CarrierDetector] Threshold: " + String.format("%.6f", threshold));
            System.out.println("[CarrierDetector] Energy ratio: " + String.format("%.2f", carrierEnergy / Math.max(totalEnergy, 1e-10)));
        }

        // The carrier must be significantly stronger than the average noise
        boolean detected = carrierEnergy > threshold && carrierEnergy > totalEnergy * 5.0;
        
        if (DEBUG) {
            System.out.println("[CarrierDetector] Result: " + (detected ? "DETECTED" : "NOT DETECTED"));
        }
        
        return detected;
    }

    /**
     * Computes the energy spectrum via a simple FFT.
     */
    private static double[] computeEnergySpectrum(float[] samples, int fftSize) {
        int n = Math.min(samples.length, fftSize);
        
        // Hanning window to reduce side lobes
        double[] window = hanningWindow(n);
        double[] real = new double[fftSize];
        double[] imag = new double[fftSize];

        for (int i = 0; i < n; i++) {
            real[i] = samples[i] * window[i];
        }

        // Simple FFT (Cooley-Tukey algorithm)
        fft(real, imag);

        // Compute the energy (squared magnitude) for each bin
        double[] energy = new double[fftSize / 2];
        for (int i = 0; i < energy.length; i++) {
            energy[i] = real[i] * real[i] + imag[i] * imag[i];
        }

        return energy;
    }

    /**
     * In-place FFT (Cooley-Tukey radix-2 decimation-in-time).
     */

    private static void fft(double[] real, double[] imag) {
        int n = real.length;
        if (n <= 1) {
            return;
        }

        // Bit-reversal permutation
        int j = 0;
        for (int i = 0; i < n - 1; i++) {
            if (i < j) {
                double tempReal = real[i];
                double tempImag = imag[i];
                real[i] = real[j];
                imag[i] = imag[j];
                real[j] = tempReal;
                imag[j] = tempImag;
            }
            int k = n / 2;
            while (k <= j) {
                j -= k;
                k /= 2;
            }
            j += k;
        }

        // FFT computation
        for (int len = 2; len <= n; len *= 2) {
            double angle = -2.0 * Math.PI / len;
            double wlenReal = Math.cos(angle);
            double wlenImag = Math.sin(angle);

            for (int i = 0; i < n; i += len) {
                double wReal = 1.0;
                double wImag = 0.0;

                for (int k = 0; k < len / 2; k++) {
                    int idx1 = i + k;
                    int idx2 = i + k + len / 2;

                    double tReal = wReal * real[idx2] - wImag * imag[idx2];
                    double tImag = wReal * imag[idx2] + wImag * real[idx2];

                    real[idx2] = real[idx1] - tReal;
                    imag[idx2] = imag[idx1] - tImag;
                    real[idx1] = real[idx1] + tReal;
                    imag[idx1] = imag[idx1] + tImag;

                    double wTempReal = wReal * wlenReal - wImag * wlenImag;
                    wImag = wReal * wlenImag + wImag * wlenReal;
                    wReal = wTempReal;
                }
            }
        }
    }

    /**
     * Creates a Hanning window.
     */
    private static double[] hanningWindow(int size) {
        double[] window = new double[size];
        for (int i = 0; i < size; i++) {
            window[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (size - 1)));
        }
        return window;
    }

    /**
     * Finds the next power of two.
     */
    private static int nextPowerOfTwo(int n) {
        int power = 1;
        while (power < n) {
            power *= 2;
        }
        return power;
    }
}
