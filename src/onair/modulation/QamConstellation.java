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
 * QAM constellation for orders M = 2^n with Gray coding.
 * Gray coding minimizes bit errors by ensuring that adjacent
 * symbols differ by only a single bit.
 */
public final class QamConstellation {

    private final int order;
    private final int bitsPerSymbol;
    private final double[][] points;
    private final int[] grayToBinary;
    private final int[] binaryToGray;

    public QamConstellation(int order) {
        this.order = order;
        this.bitsPerSymbol = Integer.numberOfTrailingZeros(order);
        if (order <= 0 || (order & (order - 1)) != 0) {
            throw new IllegalArgumentException("QAM order must be a power of 2.");
        }
        
        // Gray <-> Binary conversion tables
        this.grayToBinary = new int[order];
        this.binaryToGray = new int[order];
        buildGrayTables();

        // Build the constellation with Gray mapping
        this.points = buildPoints(order);

    }

    public int getOrder() {
        return order;
    }

    public int getBitsPerSymbol() {
        return bitsPerSymbol;
    }

    public double getIn(int symbolIndex) {
        return points[symbolIndex][0];
    }

    public double getQu(int symbolIndex) {
        return points[symbolIndex][1];
    }

    /**
     * Finds the nearest symbol in the constellation (hard decision).
     */
    public int nearestSymbolIndex(double in, double qu) {
        int bestIndex = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < points.length; i++) {
            double di = in - points[i][0];
            double dq = qu - points[i][1];
            double distance = di * di + dq * dq;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    /**
     * Converts bits (Gray coding) into a symbol index.
     */
    public int symbolIndexFromBits(int grayBits) {
        int binary = grayToBinary[grayBits & (order - 1)];
        return binary;
    }

    /**
     * Converts a symbol index into bits (Gray coding).
     */
    public int bitsFromSymbolIndex(int symbolIndex) {
        int binary = symbolIndex & (order - 1);
        return binaryToGray[binary];
    }

    /**
     * Builds the Gray <-> Binary conversion tables.
     */
    private void buildGrayTables() {
        // For square QAM, use a 2D Gray code
        if (order == 4 || order == 16 || order == 64 || order == 256) {
            buildSquareGrayTables();
        } else {
            // Otherwise, standard 1D Gray code
            buildStandardGrayTables();
        }
    }

    /**
     * 2D Gray code for square QAM (4, 16, 64, 256-QAM).
     */
    private void buildSquareGrayTables() {
        int side = (int) Math.sqrt(order);
        int bitsPerDim = bitsPerSymbol / 2;
        
        // 1D Gray code for each dimension (I and Q)
        int[] gray1D = new int[side];
        for (int i = 0; i < side; i++) {
            gray1D[i] = i ^ (i >> 1);  // Standard binary -> Gray conversion
        }
        
        // Combine the I and Q Gray codes
        for (int row = 0; row < side; row++) {
            for (int col = 0; col < side; col++) {
                int binaryIndex = row * side + col;
                int grayI = gray1D[col];
                int grayQ = gray1D[row];
                int grayBits = (grayQ << bitsPerDim) | grayI;
                
                grayToBinary[grayBits] = binaryIndex;
                binaryToGray[binaryIndex] = grayBits;
            }
        }
    }

    /**
     * Standard 1D Gray code for PSK and other modulations.
     */
    private void buildStandardGrayTables() {
        for (int i = 0; i < order; i++) {
            int gray = i ^ (i >> 1);
            grayToBinary[gray] = i;
            binaryToGray[i] = gray;
        }
    }

    private static double[][] buildPoints(int order) {
        if (order == 2) {
            // BPSK
            return new double[][]{{-1.0, 0.0}, {1.0, 0.0}};
        }
        if (order == 4) {
            // QPSK
            return new double[][]{
                    {-1.0, -1.0},  // 00
                    {-1.0, 1.0},   // 01
                    {1.0, -1.0},   // 10
                    {1.0, 1.0}     // 11
            };
        }

        int side = (int) Math.sqrt(order);
        if (side * side == order) {
            return buildSquareQam(side);
        }
        return buildPsk(order);
    }

    private static double[][] buildSquareQam(int side) {
        double[][] points = new double[side * side][2];
        double scale = 2.0 / (side - 1);
        int index = 0;
        for (int row = 0; row < side; row++) {
            for (int col = 0; col < side; col++) {
                double in = -1.0 + col * scale;
                double qu = -1.0 + row * scale;
                points[index++] = new double[]{in, qu};
            }
        }
        normalizePower(points);
        return points;
    }

    private static double[][] buildPsk(int order) {
        double[][] points = new double[order][2];
        for (int i = 0; i < order; i++) {
            double angle = 2.0 * Math.PI * i / order;
            points[i] = new double[]{Math.cos(angle), Math.sin(angle)};
        }
        return points;
    }

    private static void normalizePower(double[][] points) {
        double averagePower = 0.0;
        for (double[] point : points) {
            averagePower += point[0] * point[0] + point[1] * point[1];
        }
        averagePower /= points.length;
        double scale = 1.0 / Math.sqrt(averagePower);
        for (double[] point : points) {
            point[0] *= scale;
            point[1] *= scale;
        }
    }
    
    /**
     * Prints all I/Q coordinates of the constellation for diagnostics.
     */
    public void logConstellationPoints() {
        System.out.println("\n=== CONSTELLATION QAM-" + order + " (I/Q coordinates) ===");
        for (int i = 0; i < order; i++) {
            System.out.printf("SymbolIdx %2d: I=%7.3f Q=%7.3f%n", i, points[i][0], points[i][1]);
        }
        System.out.println("=====================================================\n");
    }
    
    /**
     * Validates and prints the Gray tables for diagnostics.
     */
    private void validateAndLogGrayTables() {
        System.out.println("\n=== GRAY TABLES VALIDATION (QAM-" + order + ") ===");
        
        // Check that the tables are inverses of each other
        boolean valid = true;
        for (int i = 0; i < order; i++) {
            int gray = binaryToGray[i];
            int backToBinary = grayToBinary[gray];
            if (backToBinary != i) {
                System.out.printf("ERROR: binary[%d] -> gray[%d] -> binary[%d] (should be %d)%n",
                    i, gray, backToBinary, i);
                valid = false;
            }
        }
        
        if (valid) {
            System.out.println("Gray tables valid (consistently invertible)");
        }
        
        // Show the first 16 entries
        int display = Math.min(16, order);
        System.out.println("\nFirst entries:");
        System.out.println("Binary -> Gray | Gray -> Binary");
        for (int i = 0; i < display; i++) {
            System.out.printf("  %2d -> %2d    |   %2d -> %2d%n",
                i, binaryToGray[i], i, grayToBinary[i]);
        }
        System.out.println("=====================================\n");
    }
}
