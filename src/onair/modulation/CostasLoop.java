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
 * Costas loop for carrier phase synchronization.
 * A Phase-Locked Loop (PLL) adapted for QAM modulations.
 * Uses a hard-decision-based phase detector to automatically
 * correct the phase offset.
 */
public class CostasLoop {
    
    private final double loopBandwidth;
    private final double dampingFactor;
    private final double alpha;  // Proportional gain
    private final double beta;   // Integrator gain
    private final QamConstellation constellation;
    
    private double phase;        // Accumulated phase
    private double frequency;    // Normalized frequency (frequency error)
    
    /**
     * @param loopBandwidth Loop bandwidth (normalized, typically 0.001 to 0.1)
     * @param dampingFactor Damping factor (typically 0.707 for critical response)
     * @param constellation Constellation for hard decision
     */
    public CostasLoop(double loopBandwidth, double dampingFactor, QamConstellation constellation) {
        this.loopBandwidth = loopBandwidth;
        this.dampingFactor = dampingFactor;
        this.constellation = constellation;
        
        // Compute the loop filter gains (2nd order)
        double denom = 1.0 + 2.0 * dampingFactor * loopBandwidth + loopBandwidth * loopBandwidth;
        this.alpha = (4 * dampingFactor * loopBandwidth) / denom;
        this.beta = (4 * loopBandwidth * loopBandwidth) / denom;
        
        this.phase = 0.0;
        this.frequency = 0.0;
    }
    
    /**
     * Processes an I/Q sample and corrects its phase.
     * @param in In-phase component (I)
     * @param qu Quadrature component (Q)
     * @return Corrected sample [I', Q']
     */
    public double[] process(double in, double qu) {
        // Inverse rotation by the estimated phase
        double cosPhase = Math.cos(-phase);
        double sinPhase = Math.sin(-phase);
        
        double inCorrected = in * cosPhase - qu * sinPhase;
        double quCorrected = in * sinPhase + qu * cosPhase;
        
        // Phase error detector (hard decision)
        double phaseError = computePhaseError(inCorrected, quCorrected);
        
        // Loop update (PI filter)
        frequency += beta * phaseError;
        phase += alpha * phaseError + frequency;
        
        // Normalize the phase between -pi and pi
        while (phase > Math.PI) phase -= 2.0 * Math.PI;
        while (phase < -Math.PI) phase += 2.0 * Math.PI;
        
        return new double[]{inCorrected, quCorrected};
    }
    
    /**
     * Computes the phase error based on a hard decision.
     * For QAM, a decision-directed detector is used.
     */
    private double computePhaseError(double in, double qu) {
        // Hard decision: find the nearest symbol
        int symbolIndex = constellation.nearestSymbolIndex(in, qu);
        double inIdeal = constellation.getIn(symbolIndex);
        double quIdeal = constellation.getQu(symbolIndex);

        // Decision-directed phase error = Im{ r * conj(ideal) } / |ideal|^2
        //   r = in + j*qu ,  ideal = inIdeal + j*quIdeal
        //   Im{r * conj(ideal)} = qu*inIdeal - in*quIdeal
        // (The previous sign was inverted -> positive feedback -> divergence.)
        // Normalizing by |ideal|^2 stabilizes the loop gain between the inner
        // and outer points of the QAM constellation.
        double error = qu * inIdeal - in * quIdeal;
        double idealEnergy = inIdeal * inIdeal + quIdeal * quIdeal;
        if (idealEnergy > 1e-12) {
            error /= idealEnergy;
        }
        return error;
    }

    
    /**
     * Resets the loop.
     */
    public void reset() {
        this.phase = 0.0;
        this.frequency = 0.0;
    }
    
    /**
     * Gets the current estimated phase.
     */
    public double getPhase() {
        return phase;
    }
    
    /**
     * Gets the estimated frequency error.
     */
    public double getFrequency() {
        return frequency;
    }
    
    /**
     * Sets the initial phase (useful after coarse detection).
     */
    public void setPhase(double phase) {
        this.phase = phase;
    }
}
