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
package onair.audio;

import onair.crypto.EncryptionSettings;
import onair.modulation.ModulationConfig;
import onair.modulation.QamDemodulator;
import onair.modulation.QamModulator;
import onair.protocol.OnAirProtocol;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;



/**
 * Audio input/output handling and QAM modulation.
 */
public class AudioEngine {

    public interface FrameListener {
        void onFrameReceived(OnAirProtocol.OnAirFrame frame);
    }

    public interface StatusListener {
        void onStatus(String message);
    }

    public interface DiagnosticsListener {
        void onDiagnostics(double rms, double bestCorrelation);
    }


    private volatile ModulationConfig config = new ModulationConfig();
    private volatile EncryptionSettings encryptionSettings = new EncryptionSettings();
    private final AtomicBoolean onAir = new AtomicBoolean(false);
    private final AtomicBoolean receiving = new AtomicBoolean(false);
    private volatile boolean testMode = false;
    private volatile String testWavPath = "onair_test.wav";

    private Thread receiveThread;
    private Thread demodThread;
    private BlockingQueue<float[]> sampleQueue;
    private long lastRxWarn;
    private TargetDataLine inputLine;
    private FrameListener frameListener;


    private StatusListener statusListener;
    private DiagnosticsListener diagnosticsListener;


    // Selected audio devices (null = system default device).
    private volatile Mixer.Info inputMixerInfo;
    private volatile Mixer.Info outputMixerInfo;


    public void setConfig(ModulationConfig config) {
        this.config = config.copy();
    }

    public ModulationConfig getConfig() {
        return config.copy();
    }

    public void setEncryptionSettings(EncryptionSettings encryptionSettings) {
        this.encryptionSettings = encryptionSettings.copy();
    }

    public EncryptionSettings getEncryptionSettings() {
        return encryptionSettings.copy();
    }

    public void setFrameListener(FrameListener frameListener) {
        this.frameListener = frameListener;
    }

    public void setStatusListener(StatusListener statusListener) {
        this.statusListener = statusListener;
    }

    public void setDiagnosticsListener(DiagnosticsListener diagnosticsListener) {
        this.diagnosticsListener = diagnosticsListener;
    }

    private void attachDiagnostics(QamDemodulator demodulator) {
        DiagnosticsListener listener = diagnosticsListener;
        if (listener != null) {
            demodulator.setDiagnosticsListener(listener::onDiagnostics);
        }
        // Fired ONCE per burst that was detected (preamble locked) but could not
        // be decoded. This is the only place the "decoding failed" hint is now
        // emitted -> no more per-block spam, and never while a message actually
        // decodes.
        demodulator.setBurstListener(correlation -> {
            long now = System.currentTimeMillis();
            if (now - lastRxWarn > 800) {
                lastRxWarn = now;
                notifyStatus(String.format(
                        "Preamble detected (%.0f%%) but decoding failed - lower the QAM order "
                                + "(\"Acoustic Preset\" button, identical on both sides).",
                        correlation * 100.0));
            }
        });
    }


    /** Sets the input device (microphone). null = default device. */
    public void setInputMixer(Mixer.Info mixerInfo) {
        this.inputMixerInfo = mixerInfo;
    }

    /** Sets the output device (speaker). null = default device. */
    public void setOutputMixer(Mixer.Info mixerInfo) {
        this.outputMixerInfo = mixerInfo;
    }


    public boolean isOnAir() {
        return onAir.get();
    }

    public void setTestMode(boolean enabled) {
        this.testMode = enabled;
    }

    public boolean isTestMode() {
        return testMode;
    }

    public void setTestWavPath(String path) {
        this.testWavPath = path;
    }

    public String getTestWavPath() {
        return testWavPath;
    }

    public synchronized void startOnAir() throws LineUnavailableException {
        if (onAir.get()) {
            return;
        }
        AudioFormat format = createFormat(config);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Audio input not supported for format " + format);
        }

        inputLine = (TargetDataLine) openLine(info, inputMixerInfo);
        // Enlarged line buffer (~0.5 s) to absorb scheduler jitter and avoid
        // microphone overruns (= samples lost in the middle of a frame).
        int lineBufferBytes = (int) (format.getSampleRate() * format.getFrameSize() * 0.5);
        try {
            inputLine.open(format, lineBufferBytes);
        } catch (IllegalArgumentException | LineUnavailableException ex) {
            // Buffer size not accepted by the device: fall back to the default.
            inputLine.open(format);
        }

        sampleQueue = new LinkedBlockingQueue<>();
        inputLine.start();
        onAir.set(true);
        receiving.set(true);

        // Lightweight thread: ONLY reads the line and pushes samples.
        receiveThread = new Thread(this::captureLoop, "onair-capture");
        receiveThread.setDaemon(true);
        receiveThread.start();

        // Heavy thread: demodulation/decoding outside the capture loop.
        demodThread = new Thread(this::demodLoop, "onair-demod");
        demodThread.setDaemon(true);
        demodThread.start();

        notifyStatus("ON-AIR reception started.");
    }


    public synchronized void stopOnAir() {
        onAir.set(false);
        receiving.set(false);

        if (receiveThread != null) {
            receiveThread.interrupt();
            try {
                receiveThread.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            receiveThread = null;
        }

        if (demodThread != null) {
            demodThread.interrupt();
            try {
                demodThread.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            demodThread = null;
        }

        if (sampleQueue != null) {
            sampleQueue.clear();
            sampleQueue = null;
        }

        if (inputLine != null) {
            inputLine.stop();
            inputLine.close();
            inputLine = null;
        }
        notifyStatus("ON-AIR reception stopped.");

    }

    public void transmit(String recipient, String sender, String message) throws LineUnavailableException {
        byte[] payload = OnAirProtocol.encode(recipient, sender, message, encryptionSettings);
        QamModulator modulator = new QamModulator(config);
        float[] samples = modulator.modulate(payload);
        
        if (testMode) {
            // Test mode: save to a WAV file
            try {
                File wavFile = new File(testWavPath);
                WavFile.write(samples, config.getSampleRateHz(), wavFile);
                notifyStatus("Message saved to " + wavFile.getAbsolutePath() + " (" + modulator.estimateDurationMs(payload) + " ms).");
            } catch (IOException e) {
                throw new LineUnavailableException("WAV save error: " + e.getMessage());
            }
        } else {
            // Normal mode: play to the audio output
            playSamples(samples);
            notifyStatus("Message modulated and transmitted (" + modulator.estimateDurationMs(payload) + " ms).");
        }
    }

    /**
     * Reads and demodulates a WAV file in test mode.
     */
    public void receiveFromWavFile() {
        if (!testMode) {
            notifyStatus("Test mode must be enabled to read a WAV file.");
            return;
        }

        try {
            File wavFile = new File(testWavPath);
            if (!wavFile.exists()) {
                notifyStatus("WAV file not found: " + wavFile.getAbsolutePath());
                return;
            }

            float[] samples = WavFile.read(wavFile);
            notifyStatus("Reading " + wavFile.getName() + " (" + samples.length + " samples)...");

            QamDemodulator demodulator = new QamDemodulator(config);
            List<OnAirProtocol.OnAirFrame> frames = demodulator.processAudio(samples, encryptionSettings);

            if (frames.isEmpty()) {
                notifyStatus("No ONAIR frame detected in the WAV file.");
            } else {
                for (OnAirProtocol.OnAirFrame frame : frames) {
                    FrameListener listener = frameListener;
                    if (listener != null) {
                        listener.onFrameReceived(frame);
                    }
                }
                notifyStatus("WAV file processed: " + frames.size() + " message(s) received.");
            }
        } catch (IOException e) {
            notifyStatus("WAV read error: " + e.getMessage());
        }
    }

    /**
     * CAPTURE thread: reads the microphone line in small blocks and pushes the
     * samples to the queue. It does NO heavy processing -> the line never overruns.
     */
    private void captureLoop() {
        int bufferSize = Math.max(4096, config.samplesPerSymbol() * 32);
        byte[] byteBuffer = new byte[bufferSize];

        // Safety limit: never accumulate more than ~10 s of pending audio.
        // Each block ~= bufferSize/2 samples; we bound it in number of blocks.
        final int maxQueuedChunks = Math.max(64, (int) (config.getSampleRateHz() * 10L / (bufferSize / 2)));

        while (receiving.get() && !Thread.currentThread().isInterrupted()) {
            TargetDataLine line = inputLine;
            if (line == null) {
                break;
            }
            int read = line.read(byteBuffer, 0, byteBuffer.length);
            if (read <= 0) {
                continue;
            }

            int sampleCount = read / 2;
            float[] floats = new float[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                int lo = byteBuffer[i * 2] & 0xFF;
                int hi = byteBuffer[i * 2 + 1];
                short sample = (short) ((hi << 8) | lo);
                floats[i] = sample / 32768.0f;
            }

            BlockingQueue<float[]> queue = sampleQueue;
            if (queue == null) {
                break;
            }
            // Anti-congestion: if demodulation falls behind, drop the oldest
            // blocks to stay in real time.
            while (queue.size() >= maxQueuedChunks) {
                queue.poll();
            }
            queue.offer(floats);
        }
    }

    /**
     * DEMODULATION thread: consumes blocks from the queue and does all the heavy
     * processing (FIR, correlation, decoding), away from the capture.
     */
    private void demodLoop() {
        QamDemodulator demodulator = new QamDemodulator(config);
        attachDiagnostics(demodulator);
        String activeConfigKey = configKey(config);

        while (receiving.get() && !Thread.currentThread().isInterrupted()) {
            float[] chunk;
            try {
                BlockingQueue<float[]> queue = sampleQueue;
                if (queue == null) {
                    break;
                }
                chunk = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (chunk == null) {
                continue;
            }

            String currentKey = configKey(config);
            if (!currentKey.equals(activeConfigKey)) {
                demodulator = new QamDemodulator(config);
                demodulator.reset();
                attachDiagnostics(demodulator);
                activeConfigKey = currentKey;
                BlockingQueue<float[]> queue = sampleQueue;
                if (queue != null) {
                    queue.clear();
                }
            }

            try {
                List<OnAirProtocol.OnAirFrame> frames =
                        demodulator.processAudio(chunk, encryptionSettings);
                if (!frames.isEmpty()) {
                    for (OnAirProtocol.OnAirFrame frame : frames) {
                        FrameListener listener = frameListener;
                        if (listener != null) {
                            listener.onFrameReceived(frame);
                        }
                    }
                    // Burst consumed: drop any chunks of the SAME burst still in
                    // the queue so its tail is not re-processed (which would emit
                    // a spurious "preamble detected but decoding failed").
                    BlockingQueue<float[]> queue = sampleQueue;
                    if (queue != null) {
                        queue.clear();
                    }
                }
                // The "preamble detected but decoding failed" hint is now emitted
                // by the demodulator's BurstListener (exactly once, at burst end),
                // see attachDiagnostics(). No per-block warning here anymore.
            } catch (Throwable t) {

                // A faulty block must NEVER kill the receive thread:
                // restart from a clean demodulator and keep listening.
                notifyStatus("Reception: block skipped (" + t.getClass().getSimpleName()
                        + (t.getMessage() != null ? " : " + t.getMessage() : "") + ").");
                demodulator = new QamDemodulator(config);
                demodulator.reset();
                attachDiagnostics(demodulator);
            }
        }
    }



    private void playSamples(float[] samples) throws LineUnavailableException {
        AudioFormat format = createFormat(config);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Audio output not supported for format " + format);
        }

        try (SourceDataLine line = (SourceDataLine) openLine(info, outputMixerInfo)) {

            line.open(format);
            line.start();

            byte[] pcm = floatsToPcm16(samples);
            int offset = 0;
            while (offset < pcm.length) {
                offset += line.write(pcm, offset, pcm.length - offset);
            }
            line.drain();
        }
    }

    private static AudioFormat createFormat(ModulationConfig config) {
        return new AudioFormat(
                config.getSampleRateHz(),
                16,
                1,
                true,
                false
        );
    }

    private static byte[] floatsToPcm16(float[] samples) {
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            float clamped = Math.max(-1.0f, Math.min(1.0f, samples[i]));
            short value = (short) (clamped * 32767.0f);
            pcm[i * 2] = (byte) (value & 0xFF);
            pcm[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return pcm;
    }

    private static float[] copyOf(float[] source, int length) {
        float[] copy = new float[length];
        System.arraycopy(source, 0, copy, 0, length);
        return copy;
    }

    private void notifyStatus(String message) {
        StatusListener listener = statusListener;
        if (listener != null) {
            listener.onStatus(message);
        }
    }

    private static String configKey(ModulationConfig config) {
        return config.getSampleRateHz() + "|"
                + config.getCarrierFrequencyHz() + "|"
                + config.getSymbolsPerSecond() + "|"
                + config.getQamOrder() + "|"
                + config.getFilterRollOff() + "|"
                + config.getFilterSpanSymbols();
    }

    /** Opens a line on the given mixer (or the default device if null). */
    private static javax.sound.sampled.Line openLine(DataLine.Info info, Mixer.Info mixerInfo)
            throws LineUnavailableException {
        if (mixerInfo == null) {
            return AudioSystem.getLine(info);
        }
        Mixer mixer = AudioSystem.getMixer(mixerInfo);
        if (!mixer.isLineSupported(info)) {
            throw new LineUnavailableException(
                    "The device \"" + mixerInfo.getName() + "\" does not support this audio format.");
        }
        return mixer.getLine(info);
    }

    /** Lists the input devices (microphone) supporting the current format. */
    public List<Mixer.Info> listInputDevices() {
        return listDevices(new DataLine.Info(TargetDataLine.class, createFormat(config)));
    }

    /** Lists the output devices (speaker) supporting the current format. */
    public List<Mixer.Info> listOutputDevices() {
        return listDevices(new DataLine.Info(SourceDataLine.class, createFormat(config)));
    }

    private static List<Mixer.Info> listDevices(DataLine.Info lineInfo) {
        List<Mixer.Info> result = new ArrayList<>();
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (mixer.isLineSupported(lineInfo)) {
                    result.add(mixerInfo);
                }
            } catch (Exception ignored) {
                // Mixer not accessible: ignore it.
            }
        }
        return result;
    }
}
