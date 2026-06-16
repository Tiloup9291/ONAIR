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

import onair.audio.WavFile;
import onair.crypto.EncryptionSettings;
import onair.modulation.ModulationConfig;
import onair.modulation.QamDemodulator;
import onair.modulation.QamModulator;
import onair.protocol.OnAirProtocol;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * End-to-end tests of the QAM chain.
 *
 * 1) Perfect loopback via WAV file (reproduces the app's "Test Mode").
 * 2) Realistic channel + FRAGMENTED STREAMING reception: attenuation, noise,
 *    resampling (= clock mismatch that induces BOTH symbol clock drift AND a
 *    carrier frequency offset / CFO), silence before/after. Validates Gardner
 *    (timing) + Costas (carrier) + streaming buffer.
 * 3) Noise only: checks that no ghost frame is decoded (squelch + CRC).
 *
 * Usage: java -cp out onair.SelfTest
 */
public final class SelfTest {

    private SelfTest() {
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;

        System.out.println("=== 1. Perfect loopback (WAV) ===");
        failures += runLoopback("QAM-16 plain", 4, "Bob", "Alice", "Hello ONAIR!", null);
        failures += runLoopback("QPSK plain", 2, "Receiver", "Sender",
                "The brown fox jumps over the dog.", null);
        failures += runLoopback("QAM-64 plain", 6, "RX", "TX", "1234567890", null);
        failures += runLoopback("QAM-16 encrypted", 4, "Bob", "Alice", "Secret message 42",
                "my-shared-secret-key");

        System.out.println();
        System.out.println("=== 2. Realistic channel + fragmented streaming reception ===");
        // rho = clock factor (1 + ppm). At fc=2000 Hz: CFO ~= fc*(rho-1).
        failures += runChannel("QPSK  +light noise", 2, "RX", "TX",
                "Hello world, this is a radio test.", null,
                /*rho*/ 1.0002, /*gain*/ 0.6, /*noise*/ 0.006);
        failures += runChannel("QAM-16 +CFO+drift", 4, "Bob", "Alice", "Hello ONAIR via modem!", null,
                /*rho*/ 1.0004, /*gain*/ 0.5, /*noise*/ 0.005);
        failures += runChannel("QAM-16 encrypted chan", 4, "Bob", "Alice", "Secret module 7",
                "my-shared-secret-key",
                /*rho*/ 1.0003, /*gain*/ 0.55, /*noise*/ 0.004);

        System.out.println();
        System.out.println("=== 3. Robustness: noise only (no frame expected) ===");
        failures += runNoiseOnly("Noise only");

        System.out.println();
        System.out.println("=== 4. Two successive messages (silence + continuous noise between) ===");
        failures += runTwoMessages("QAM-16 two msgs", 4, "Bob", "Alice",
                "First message before the gap.", "Second message after the gap.", null);
        failures += runTwoMessages("QPSK two msgs (enc)", 2, "RX", "TX",
                "Alpha one", "Bravo two", "my-shared-secret-key");

        System.out.println();
        System.out.println("=== 5. Five successive messages (acoustic-like noise + gaps) ===");
        failures += runManyMessages("QPSK x5", 2, "RX", "TX", 5, null);
        failures += runManyMessages("QAM-16 x5 (enc)", 4, "Bob", "Alice", 5, "shared-key-xyz");

        System.out.println();
        System.out.println("=== 6. Undecodable burst must NOT block the next good one ===");
        failures += runUndecodableThenGood("QPSK recover", 2, "RX", "TX",
                "This good message must still arrive.", null);

        System.out.println();
        if (failures == 0) {
            System.out.println("OVERALL RESULT: ALL TESTS PASS");
        } else {
            System.out.println("OVERALL RESULT: " + failures + " test(s) failed");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    // ───────────────────────── Perfect loopback case ─────────────────────────

    private static int runLoopback(String name, int bitsPerSymbol, String recipient,
                                   String sender, String message, String key) throws Exception {
        ModulationConfig config = new ModulationConfig();
        config.setBitsPerSymbol(bitsPerSymbol);

        EncryptionSettings encryption = new EncryptionSettings();
        if (key != null) {
            encryption.setEncrypted(true);
            encryption.setKey(key);
        }

        byte[] payload = OnAirProtocol.encode(recipient, sender, message, encryption);
        QamModulator modulator = new QamModulator(config);
        float[] samples = modulator.modulate(payload);

        File wav = File.createTempFile("onair_selftest", ".wav");
        wav.deleteOnExit();
        WavFile.write(samples, config.getSampleRateHz(), wav);
        float[] readBack = WavFile.read(wav);

        QamDemodulator demodulator = new QamDemodulator(config);
        List<OnAirProtocol.OnAirFrame> frames = demodulator.processAudio(readBack, encryption);
        return report(name, frames, recipient, sender, message);
    }

    // ───────────────────── Realistic channel + streaming case ─────────────────────

    private static int runChannel(String name, int bitsPerSymbol, String recipient, String sender,
                                  String message, String key,
                                  double rho, double gain, double noise) {
        try {
            ModulationConfig config = new ModulationConfig();
            config.setBitsPerSymbol(bitsPerSymbol);

            EncryptionSettings encryption = new EncryptionSettings();
            if (key != null) {
                encryption.setEncrypted(true);
                encryption.setKey(key);
            }

            byte[] payload = OnAirProtocol.encode(recipient, sender, message, encryption);
            float[] clean = new QamModulator(config).modulate(payload);

            float[] rx = applyChannel(clean, rho, gain, noise,
                    /*padFront*/ 4000, /*padBack*/ 4000, /*seed*/ 12345);

            // Streaming reception: split into blocks like a real sound card.
            QamDemodulator demod = new QamDemodulator(config);
            List<OnAirProtocol.OnAirFrame> frames = feedFragmented(demod, rx, encryption, 2048);
            return report(name, frames, recipient, sender, message);
        } catch (Exception ex) {
            System.out.printf("[FAIL] %-20s : exception %s%n", name, ex);
            return 1;
        }
    }

    private static int runNoiseOnly(String name) {
        ModulationConfig config = new ModulationConfig();
        config.setBitsPerSymbol(4);
        EncryptionSettings encryption = new EncryptionSettings();

        Random rnd = new Random(999);
        float[] noise = new float[config.getSampleRateHz()]; // 1 s of noise
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (float) (0.05 * rnd.nextGaussian());
        }

        QamDemodulator demod = new QamDemodulator(config);
        List<OnAirProtocol.OnAirFrame> frames = feedFragmented(demod, noise, encryption, 2048);
        boolean ok = frames.isEmpty();
        System.out.printf("[%s] %-20s : %s%n", ok ? "OK " : "FAIL", name,
                ok ? "no ghost frame" : (frames.size() + " ghost frame(s)!"));
        return ok ? 0 : 1;
    }

    // ───────────────── Two successive messages case ─────────────────

    /**
     * Two bursts separated by silence WITH a continuous background noise floor
     * (acoustic-coupling-like): noise + burst1 + noise(gap) + burst2 + noise.
     * Validates that the streaming receiver:
     *   - decodes BOTH messages (it keeps working after the first one), and
     *   - emits NO ghost frame in the silence/noise segments.
     */
    private static int runTwoMessages(String name, int bitsPerSymbol, String recipient, String sender,
                                      String message1, String message2, String key) {
        try {
            ModulationConfig config = new ModulationConfig();
            config.setBitsPerSymbol(bitsPerSymbol);

            EncryptionSettings encryption = new EncryptionSettings();
            if (key != null) {
                encryption.setEncrypted(true);
                encryption.setKey(key);
            }

            byte[] p1 = OnAirProtocol.encode(recipient, sender, message1, encryption);
            byte[] p2 = OnAirProtocol.encode(recipient, sender, message2, encryption);
            float[] burst1 = new QamModulator(config).modulate(p1);
            float[] burst2 = new QamModulator(config).modulate(p2);

            // Continuous background noise floor over the whole timeline (kept just
            // above what a real mic would pick up, so the squelch may stay open).
            double noiseAmp = 0.01;
            int gap = config.getSampleRateHz();          // 1 s of silence+noise between
            int pad = config.getSampleRateHz() / 2;      // 0.5 s before and after
            double gain = 0.6;

            Random rnd = new Random(2024);
            int total = pad + burst1.length + gap + burst2.length + pad;
            float[] rx = new float[total];
            for (int i = 0; i < total; i++) {
                rx[i] = (float) (noiseAmp * rnd.nextGaussian());
            }
            int off1 = pad;
            for (int i = 0; i < burst1.length; i++) {
                rx[off1 + i] += (float) (gain * burst1[i]);
            }
            int off2 = pad + burst1.length + gap;
            for (int i = 0; i < burst2.length; i++) {
                rx[off2 + i] += (float) (gain * burst2[i]);
            }

            // Streaming reception, fragmented like a real sound card.
            QamDemodulator demod = new QamDemodulator(config);
            List<OnAirProtocol.OnAirFrame> frames = feedFragmented(demod, rx, encryption, 2048);

            boolean got1 = false;
            boolean got2 = false;
            int valid = 0;
            for (OnAirProtocol.OnAirFrame f : frames) {
                boolean addressed = f.getRecipient().equals(recipient) && f.getSender().equals(sender);
                if (addressed && f.getMessage().equals(message1)) {
                    got1 = true;
                    valid++;
                } else if (addressed && f.getMessage().equals(message2)) {
                    got2 = true;
                    valid++;
                }
            }
            // Both messages must be decoded, and no extra (ghost) frame must appear.
            boolean ok = got1 && got2 && frames.size() == valid && valid == 2;
            System.out.printf("[%s] %-20s : %s%n", ok ? "OK " : "FAIL", name,
                    ok ? "both messages decoded, no ghost frame"
                       : ("msg1=" + got1 + " msg2=" + got2 + " totalFrames=" + frames.size()));
            if (!ok) {
                for (OnAirProtocol.OnAirFrame f : frames) {
                    System.out.printf("      decoded: %s -> %s : \"%s\"%n",
                            f.getSender(), f.getRecipient(), f.getMessage());
                }
            }
            return ok ? 0 : 1;
        } catch (Exception ex) {
            System.out.printf("[FAIL] %-20s : exception %s%n", name, ex);
            return 1;
        }
    }

    // ───────────────── Five successive messages case ─────────────────

    /**
     * N bursts separated by silence+noise gaps. Validates that the receiver
     * keeps working burst after burst (no state that freezes after a few
     * messages) and decodes EVERY message.
     */
    private static int runManyMessages(String name, int bitsPerSymbol, String recipient, String sender,
                                       int count, String key) {
        try {
            ModulationConfig config = new ModulationConfig();
            config.setBitsPerSymbol(bitsPerSymbol);

            EncryptionSettings encryption = new EncryptionSettings();
            if (key != null) {
                encryption.setEncrypted(true);
                encryption.setKey(key);
            }

            String[] messages = new String[count];
            float[][] bursts = new float[count][];
            for (int i = 0; i < count; i++) {
                messages[i] = "Message number " + (i + 1) + " of " + count + ".";
                byte[] p = OnAirProtocol.encode(recipient, sender, messages[i], encryption);
                bursts[i] = new QamModulator(config).modulate(p);
            }

            double noiseAmp = 0.01;
            double gain = 0.6;
            int gap = config.getSampleRateHz();          // 1 s between bursts
            int pad = config.getSampleRateHz() / 2;      // 0.5 s before and after

            int total = pad + pad;
            for (int i = 0; i < count; i++) {
                total += bursts[i].length + (i < count - 1 ? gap : 0);
            }

            Random rnd = new Random(7);
            float[] rx = new float[total];
            for (int i = 0; i < total; i++) {
                rx[i] = (float) (noiseAmp * rnd.nextGaussian());
            }
            int off = pad;
            for (int i = 0; i < count; i++) {
                for (int j = 0; j < bursts[i].length; j++) {
                    rx[off + j] += (float) (gain * bursts[i][j]);
                }
                off += bursts[i].length + gap;
            }

            QamDemodulator demod = new QamDemodulator(config);
            List<OnAirProtocol.OnAirFrame> frames = feedFragmented(demod, rx, encryption, 2048);

            boolean[] seen = new boolean[count];
            int decoded = 0;
            for (OnAirProtocol.OnAirFrame f : frames) {
                boolean addressed = f.getRecipient().equals(recipient) && f.getSender().equals(sender);
                if (!addressed) continue;
                for (int i = 0; i < count; i++) {
                    if (!seen[i] && f.getMessage().equals(messages[i])) {
                        seen[i] = true;
                        decoded++;
                        break;
                    }
                }
            }
            boolean ok = decoded == count;
            System.out.printf("[%s] %-20s : %s%n", ok ? "OK " : "FAIL", name,
                    ok ? (count + "/" + count + " messages decoded")
                       : (decoded + "/" + count + " decoded, totalFrames=" + frames.size()));
            return ok ? 0 : 1;
        } catch (Exception ex) {
            System.out.printf("[FAIL] %-20s : exception %s%n", name, ex);
            return 1;
        }
    }

    // ───────────── Undecodable burst then a good one ─────────────

    /**
     * A first burst whose payload is heavily corrupted (preamble kept clean so
     * it IS detected, but the data/CRC fails), followed by a clean good burst.
     * Validates the core fix: a detected-but-undecodable burst must be EVICTED
     * at its end and must NOT block (freeze) the reception of the next message.
     */
    private static int runUndecodableThenGood(String name, int bitsPerSymbol, String recipient,
                                              String sender, String goodMessage, String key) {
        try {
            ModulationConfig config = new ModulationConfig();
            config.setBitsPerSymbol(bitsPerSymbol);

            EncryptionSettings encryption = new EncryptionSettings();
            if (key != null) {
                encryption.setEncrypted(true);
                encryption.setKey(key);
            }

            byte[] pBad = OnAirProtocol.encode(recipient, sender, "Garbled payload here.", encryption);
            byte[] pGood = OnAirProtocol.encode(recipient, sender, goodMessage, encryption);
            float[] badBurst = new QamModulator(config).modulate(pBad);
            float[] goodBurst = new QamModulator(config).modulate(pGood);

            // Corrupt the data part of the bad burst (keep the first symbols, i.e.
            // the preamble region, clean so the burst is still DETECTED).
            Random crnd = new Random(13);
            int cleanHead = config.samplesPerSymbol() * 40; // > 20-symbol preamble
            for (int i = cleanHead; i < badBurst.length; i++) {
                badBurst[i] += (float) (0.9 * crnd.nextGaussian());
            }

            double noiseAmp = 0.01;
            double gain = 0.6;
            int gap = config.getSampleRateHz();
            int pad = config.getSampleRateHz() / 2;

            Random rnd = new Random(21);
            int total = pad + badBurst.length + gap + goodBurst.length + pad;
            float[] rx = new float[total];
            for (int i = 0; i < total; i++) {
                rx[i] = (float) (noiseAmp * rnd.nextGaussian());
            }
            int offBad = pad;
            for (int i = 0; i < badBurst.length; i++) {
                rx[offBad + i] += (float) (gain * badBurst[i]);
            }
            int offGood = pad + badBurst.length + gap;
            for (int i = 0; i < goodBurst.length; i++) {
                rx[offGood + i] += (float) (gain * goodBurst[i]);
            }

            QamDemodulator demod = new QamDemodulator(config);
            List<OnAirProtocol.OnAirFrame> frames = feedFragmented(demod, rx, encryption, 2048);

            boolean gotGood = false;
            for (OnAirProtocol.OnAirFrame f : frames) {
                if (f.getRecipient().equals(recipient) && f.getSender().equals(sender)
                        && f.getMessage().equals(goodMessage)) {
                    gotGood = true;
                }
            }
            boolean ok = gotGood;
            System.out.printf("[%s] %-20s : %s%n", ok ? "OK " : "FAIL", name,
                    ok ? "good message recovered after an undecodable burst"
                       : ("good message NOT recovered, totalFrames=" + frames.size()));
            return ok ? 0 : 1;
        } catch (Exception ex) {
            System.out.printf("[FAIL] %-20s : exception %s%n", name, ex);
            return 1;
        }
    }

    // ───────────────────────── Tools ─────────────────────────

    /**
     * Simulates a channel: silence + resampling (clock mismatch => symbol drift
     * + CFO) + attenuation + white noise + silence.
     */
    private static float[] applyChannel(float[] clean, double rho, double gain, double noiseAmp,
                                        int padFront, int padBack, long seed) {
        Random rnd = new Random(seed);
        int bodyLen = (int) Math.floor((clean.length - 2) / rho);
        float[] out = new float[padFront + bodyLen + padBack];

        // Silence (with a hint of noise) before.
        for (int i = 0; i < padFront; i++) {
            out[i] = (float) (noiseAmp * rnd.nextGaussian());
        }
        // Resampled body (linear interpolation) + gain + noise.
        for (int m = 0; m < bodyLen; m++) {
            double pos = m * rho;
            int base = (int) Math.floor(pos);
            double frac = pos - base;
            double s = clean[base] * (1.0 - frac) + clean[base + 1] * frac;
            out[padFront + m] = (float) (gain * s + noiseAmp * rnd.nextGaussian());
        }
        // Silence after.
        for (int i = 0; i < padBack; i++) {
            out[padFront + bodyLen + i] = (float) (noiseAmp * rnd.nextGaussian());
        }
        return out;
    }

    /** Feeds the demodulator in blocks (simulated real-time reception). */
    private static List<OnAirProtocol.OnAirFrame> feedFragmented(
            QamDemodulator demod, float[] signal, EncryptionSettings encryption, int block) {
        List<OnAirProtocol.OnAirFrame> all = new ArrayList<>();
        for (int off = 0; off < signal.length; off += block) {
            int len = Math.min(block, signal.length - off);
            float[] chunk = new float[len];
            System.arraycopy(signal, off, chunk, 0, len);
            all.addAll(demod.processAudio(chunk, encryption));
        }
        return all;
    }

    private static int report(String name, List<OnAirProtocol.OnAirFrame> frames,
                              String recipient, String sender, String message) {
        boolean ok = false;
        String got = "(no frame)";
        for (OnAirProtocol.OnAirFrame frame : frames) {
            got = frame.getSender() + " -> " + frame.getRecipient() + " : " + frame.getMessage();
            if (frame.getRecipient().equals(recipient)
                    && frame.getSender().equals(sender)
                    && frame.getMessage().equals(message)) {
                ok = true;
                break;
            }
        }
        System.out.printf("[%s] %-20s : %s%n",
                ok ? "OK " : "FAIL", name, ok ? got : ("expected \"" + message + "\", got " + got));
        return ok ? 0 : 1;
    }
}
