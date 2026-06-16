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
package onair.ui;

import onair.audio.AudioEngine;
import onair.crypto.EncryptionSettings;
import onair.modulation.ModulationConfig;
import onair.protocol.OnAirProtocol;

import javax.sound.sampled.Mixer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;


public class OnAirApplication extends JFrame {

    private final AudioEngine audioEngine = new AudioEngine();
    private final ModulationConfig modulationConfig = new ModulationConfig();
    private final EncryptionSettings encryptionSettings = new EncryptionSettings();

    private final JTextArea conversationArea = new JTextArea();
    private final JTextField recipientField = new JTextField(20);
    private final JTextField senderField = new JTextField(20);
    private final JTextField messageField = new JTextField(40);
    private final JButton onAirButton = new JButton("ON-AIR");
    private final JButton sendButton = new JButton("Send");
    private final JLabel statusLabel = new JLabel("Ready.");
    private final JLabel diagLabel = new JLabel("RX level: -- | correlation: --");
    private final JButton acousticPresetButton = new JButton("Acoustic Preset");
    private volatile long lastDiagUpdate = 0L;
    private final JTextArea license = new JTextArea("ONAIR - QAM Messenger\nCopyright (C) 2026  John Doe\n\nThis program is free software: you can redistribute it and/or modify\nit under the terms of the GNU General Public License as published by\nthe Free Software Foundation, version 3 of the License, GPL-3.0-only.\n\nThis program is distributed in the hope that it will be useful,\nbut WITHOUT ANY WARRANTY; without even the implied warranty of\nMERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the\nGNU General Public License for more details.\n\nYou should have received a copy of the GNU General Public License\nalong with this program.  If not, see <https://www.gnu.org/licenses/>\n\n");

    
    private final JCheckBox testModeCheckBox = new JCheckBox("Test Mode (WAV file)");
    private final JTextField testWavPathField = new JTextField("onair_test.wav", 20);
    private final JButton readWavButton = new JButton("Read WAV");

    private final JComboBox<DeviceItem> inputDeviceCombo = new JComboBox<>();
    private final JComboBox<DeviceItem> outputDeviceCombo = new JComboBox<>();
    private final JButton refreshDevicesButton = new JButton("Refresh list");


    private final JSpinner sampleRateSpinner = new JSpinner(new SpinnerNumberModel(44100, 8000, 192000, 1000));
    private final JSpinner carrierSpinner = new JSpinner(new SpinnerNumberModel(2000.0, 100.0, 10000.0, 50.0));
    private final JSpinner symbolsSpinner = new JSpinner(new SpinnerNumberModel(441.0, 50.0, 5000.0, 50.0));
    private final JSpinner qamBitsSpinner = new JSpinner(new SpinnerNumberModel(4, 1, ModulationConfig.MAX_BITS_PER_SYMBOL, 1));
    private final JLabel qamOrderLabel = new JLabel("QAM-16");
    private final JSpinner rollOffSpinner = new JSpinner(new SpinnerNumberModel(0.35, 0.05, 1.0, 0.05));
    private final JSpinner filterSpanSpinner = new JSpinner(new SpinnerNumberModel(8, 2, 32, 1));
    private final JSpinner correlationThresholdSpinner = new JSpinner(new SpinnerNumberModel(0.70, 0.0, 0.95, 0.05));

    private final JCheckBox encryptedCheckBox = new JCheckBox("Encrypted (AES-256)");
    private final JTextField encryptionKeyField = new JTextField(32);

    public OnAirApplication() {

        super("ONAIR - QAM Messenger");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 620));
        setLocationRelativeTo(null);

        buildUi();
        wireEvents();
        updateQamOrderLabel();
        applyConfigFromUi(false);
    }

    private void updateQamOrderLabel() {
        int bits = (Integer) qamBitsSpinner.getValue();
        int order = ModulationConfig.orderFromBits(bits);
        qamOrderLabel.setText("QAM-" + order + " (2^" + bits + ")");
    }

    private void buildUi() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Messages", buildMessagesTab());

        JScrollPane settingsScroll = new JScrollPane(
                buildSettingsTab(),
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        settingsScroll.getVerticalScrollBar().setUnitIncrement(16);
        tabs.addTab("QAM Settings", settingsScroll);
	license.setEditable(false);
	license.setOpaque(false);
	tabs.addTab("License", license);


        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        statusPanel.add(statusLabel, BorderLayout.CENTER);
        statusPanel.add(diagLabel, BorderLayout.EAST);


        getContentPane().setLayout(new BorderLayout(8, 8));
        getContentPane().add(tabs, BorderLayout.CENTER);
        getContentPane().add(statusPanel, BorderLayout.SOUTH);
    }

    private JPanel buildMessagesTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        conversationArea.setEditable(false);
        conversationArea.setLineWrap(true);
        conversationArea.setWrapStyleWord(true);
        JScrollPane conversationScroll = new JScrollPane(conversationArea);
        conversationScroll.setBorder(BorderFactory.createTitledBorder("Conversation"));

        JPanel fieldsPanel = new JPanel(new GridBagLayout());
        fieldsPanel.setBorder(BorderFactory.createTitledBorder("New message"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;

        gc.gridx = 0;
        gc.gridy = 0;
        gc.weightx = 0.0;
        fieldsPanel.add(new JLabel("Recipient:"), gc);
        gc.gridx = 1;
        gc.weightx = 1.0;
        fieldsPanel.add(recipientField, gc);

        gc.gridx = 0;
        gc.gridy = 1;
        gc.weightx = 0.0;
        fieldsPanel.add(new JLabel("Sender (local name):"), gc);
        gc.gridx = 1;
        gc.weightx = 1.0;
        fieldsPanel.add(senderField, gc);

        gc.gridx = 0;
        gc.gridy = 2;
        gc.weightx = 0.0;
        fieldsPanel.add(new JLabel("Message:"), gc);
        gc.gridx = 1;
        gc.weighty = 0.0;
        fieldsPanel.add(messageField, gc);

        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonsPanel.add(onAirButton);
        buttonsPanel.add(sendButton);
        
        JPanel testPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        testPanel.setBorder(BorderFactory.createTitledBorder("Test"));
        testPanel.add(testModeCheckBox);
        testPanel.add(new JLabel("File:"));
        testPanel.add(testWavPathField);
        testPanel.add(readWavButton);

        JPanel bottomPanel = new JPanel(new BorderLayout(8, 8));
        bottomPanel.add(fieldsPanel, BorderLayout.CENTER);
        JPanel controlsPanel = new JPanel(new BorderLayout());
        controlsPanel.add(buttonsPanel, BorderLayout.NORTH);
        controlsPanel.add(testPanel, BorderLayout.SOUTH);
        bottomPanel.add(controlsPanel, BorderLayout.SOUTH);

        panel.add(conversationScroll, BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildSettingsTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridx = 0;
        gc.gridy = 0;

        gc.gridwidth = 2;
        gc.weightx = 1.0;
        panel.add(new JLabel("Audio devices"), gc);
        gc.gridwidth = 1;
        gc.gridy++;
        addSettingRow(panel, gc, "Microphone (input):", inputDeviceCombo);
        addSettingRow(panel, gc, "Speaker (output):", outputDeviceCombo);
        gc.gridx = 1;
        gc.weightx = 1.0;
        panel.add(refreshDevicesButton, gc);
        gc.gridy++;

        gc.gridwidth = 2;
        gc.weightx = 1.0;
        panel.add(new JLabel("Modulation parameters"), gc);
        gc.gridwidth = 1;
        gc.gridy++;
        addSettingRow(panel, gc, "Sample rate (Hz):", sampleRateSpinner);

        addSettingRow(panel, gc, "Carrier frequency (Hz):", carrierSpinner);
        addSettingRow(panel, gc, "Symbols per second:", symbolsSpinner);
        addSettingRow(panel, gc, "Bits per symbol (2^n):", qamBitsSpinner);
        gc.gridx = 0;
        gc.weightx = 0.0;
        panel.add(new JLabel("Computed QAM order:"), gc);
        gc.gridx = 1;
        gc.weightx = 1.0;
        panel.add(qamOrderLabel, gc);
        gc.gridy++;
        addSettingRow(panel, gc, "Filter roll-off (alpha):", rollOffSpinner);
        addSettingRow(panel, gc, "Filter span (symbols):", filterSpanSpinner);
        addSettingRow(panel, gc, "Correlation threshold (0%-95%):", correlationThresholdSpinner);


        gc.gridx = 1;
        gc.weightx = 1.0;
        panel.add(acousticPresetButton, gc);
        gc.gridy++;

        gc.gridy++;
        gc.gridx = 0;
        gc.gridwidth = 2;
        gc.weightx = 1.0;
        panel.add(encryptedCheckBox, gc);

        gc.gridy++;

        gc.gridwidth = 1;
        addSettingRow(panel, gc, "AES-256 key:", encryptionKeyField);

        gc.gridy++;
        gc.gridx = 0;
        gc.gridwidth = 2;
        gc.weighty = 1.0;
        gc.fill = GridBagConstraints.BOTH;
        JTextArea help = new JTextArea(
                "Tips:\n"
                        + "- Use the same parameters on the transmitter and the receiver.\n"
                        + "- For a local test, route the audio output to the input (cable or virtual device).\n"
                        + "- QAM type: pick n bits per symbol to get QAM-2^n (e.g. 9 -> QAM-512).\n"
                        + "- The protocol encapsulates: SYNC! | ONAIR | Length | Flags | Recipient | Sender | Message | (Filter span + 2) alternating of QAM order diagonal corners.\n"
                        + "- Optional AES-256-GCM encryption (same key required on transmitter and receiver).\n"
                        + "- Only ONAIR packets addressed to your local name (Sender field) are displayed."
        );
        help.setEditable(false);
        help.setOpaque(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        panel.add(help, gc);
        return panel;
    }

    private void addSettingRow(JPanel panel, GridBagConstraints gc, String label, java.awt.Component field) {
        gc.gridx = 0;
        gc.weightx = 0.0;
        panel.add(new JLabel(label), gc);
        gc.gridx = 1;
        gc.weightx = 1.0;
        panel.add(field, gc);
        gc.gridy++;
    }

    private void wireEvents() {
        audioEngine.setFrameListener(this::handleIncomingFrame);
        audioEngine.setStatusListener(message -> SwingUtilities.invokeLater(() -> statusLabel.setText(message)));
        audioEngine.setDiagnosticsListener((rms, correlation) -> {
            long now = System.currentTimeMillis();
            if (now - lastDiagUpdate < 120) {
                return;
            }
            lastDiagUpdate = now;
            String corrText = Double.isNaN(correlation) ? "--" : String.format("%.0f%%", correlation * 100.0);
            SwingUtilities.invokeLater(() -> diagLabel.setText(
                    String.format("RX level: %.3f | correlation: %s", rms, corrText)));
        });

        onAirButton.addActionListener(event -> toggleOnAir());
        sendButton.addActionListener(event -> sendMessage());
        readWavButton.addActionListener(event -> readWavFile());
        acousticPresetButton.addActionListener(event -> applyAcousticPreset());


        refreshDevicesButton.addActionListener(event -> populateDevices());
        inputDeviceCombo.addActionListener(event -> {
            DeviceItem item = (DeviceItem) inputDeviceCombo.getSelectedItem();
            audioEngine.setInputMixer(item == null ? null : item.info);
        });
        outputDeviceCombo.addActionListener(event -> {
            DeviceItem item = (DeviceItem) outputDeviceCombo.getSelectedItem();
            audioEngine.setOutputMixer(item == null ? null : item.info);
        });
        populateDevices();

        
        testModeCheckBox.addActionListener(event -> {
            boolean testMode = testModeCheckBox.isSelected();
            audioEngine.setTestMode(testMode);
            testWavPathField.setEnabled(testMode);
            readWavButton.setEnabled(testMode);
            onAirButton.setEnabled(!testMode);
            statusLabel.setText(testMode ? "Test mode enabled." : "Normal mode.");
        });
        
        testWavPathField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                audioEngine.setTestWavPath(testWavPathField.getText());
            }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                audioEngine.setTestWavPath(testWavPathField.getText());
            }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                audioEngine.setTestWavPath(testWavPathField.getText());
            }
        });
        
        // Initialize the state of the test controls
        testWavPathField.setEnabled(false);
        readWavButton.setEnabled(false);

        Runnable configUpdater = () -> applyConfigFromUi(true);
        sampleRateSpinner.addChangeListener(event -> configUpdater.run());
        carrierSpinner.addChangeListener(event -> configUpdater.run());
        symbolsSpinner.addChangeListener(event -> configUpdater.run());
        qamBitsSpinner.addChangeListener(event -> {
            updateQamOrderLabel();
            configUpdater.run();
        });
        rollOffSpinner.addChangeListener(event -> configUpdater.run());
        filterSpanSpinner.addChangeListener(event -> configUpdater.run());
        correlationThresholdSpinner.addChangeListener(event -> configUpdater.run());
        encryptedCheckBox.addActionListener(event -> configUpdater.run());
        encryptionKeyField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                configUpdater.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                configUpdater.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                configUpdater.run();
            }
        });
    }

    private void toggleOnAir() {
        try {
            if (audioEngine.isOnAir()) {
                audioEngine.stopOnAir();
                onAirButton.setText("ON-AIR");
            } else {
                applyConfigFromUi(false);
                audioEngine.startOnAir();
                onAirButton.setText("OFF-AIR");
            }
        } catch (Exception ex) {
            showError("Unable to start audio reception.", ex);
        }
    }

    private void sendMessage() {
        String recipient = recipientField.getText().trim();
        String sender = senderField.getText().trim();
        String message = messageField.getText().trim();

        if (recipient.isEmpty() || sender.isEmpty() || message.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Recipient, sender and message are required.",
                    "Missing fields",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            applyConfigFromUi(false);
            encryptionSettings.validateForTransmit();
            audioEngine.transmit(recipient, sender, message);
            appendConversation("Me -> " + recipient + ": " + message);
            messageField.setText("");
        } catch (Exception ex) {
            showError("Failed to send the message.", ex);
        }
    }

    private void readWavFile() {
        if (!testModeCheckBox.isSelected()) {
            JOptionPane.showMessageDialog(this,
                    "Test mode must be enabled.",
                    "Test mode required",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            applyConfigFromUi(false);
            audioEngine.receiveFromWavFile();
        } catch (Exception ex) {
            showError("Failed to read the WAV file.", ex);
        }
    }

    private void handleIncomingFrame(OnAirProtocol.OnAirFrame frame) {
        String localName = senderField.getText().trim();
        if (localName.isEmpty()) {
            return;
        }

        if (!frame.getRecipient().equals(localName)) {
            return;
        }

        // Duplicates are displayed intentionally: every valid frame received
        // (even a repeated message) appears in the conversation.
        SwingUtilities.invokeLater(() ->
                appendConversation(frame.getSender() + " -> me: " + frame.getMessage()));

    }

    private void appendConversation(String line) {
        if (conversationArea.getText().isEmpty()) {
            conversationArea.setText(line);
        } else {
            conversationArea.append(System.lineSeparator() + line);
        }
        conversationArea.setCaretPosition(conversationArea.getDocument().getLength());
    }

    private void applyConfigFromUi(boolean notify) {
        try {
            modulationConfig.setSampleRateHz((Integer) sampleRateSpinner.getValue());
            modulationConfig.setCarrierFrequencyHz(((Number) carrierSpinner.getValue()).doubleValue());
            modulationConfig.setSymbolsPerSecond(((Number) symbolsSpinner.getValue()).doubleValue());
            modulationConfig.setBitsPerSymbol((Integer) qamBitsSpinner.getValue());
            modulationConfig.setFilterRollOff(((Number) rollOffSpinner.getValue()).doubleValue());
            modulationConfig.setFilterSpanSymbols((Integer) filterSpanSpinner.getValue());
            modulationConfig.setCorrelationThreshold(((Number) correlationThresholdSpinner.getValue()).doubleValue());
            encryptionSettings.setEncrypted(encryptedCheckBox.isSelected());
            encryptionSettings.setKey(encryptionKeyField.getText());

            audioEngine.setConfig(modulationConfig);
            audioEngine.setEncryptionSettings(encryptionSettings);
            if (notify) {
                statusLabel.setText("Settings updated.");
            }
        } catch (IllegalArgumentException ex) {
            showError("Invalid parameter.", ex);
        }
    }

    private void showError(String title, Exception ex) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                this,
                ex.getMessage(),
                title,
                JOptionPane.ERROR_MESSAGE));
    }

    /**
     * Applies a robust preset for the acoustic channel (speaker -> microphone,
     * 2 machines): QPSK, low symbol rate, low carrier, low correlation threshold.
     * Apply it IDENTICALLY on the transmitter and the receiver.
     */
    private void applyAcousticPreset() {
        carrierSpinner.setValue(1500.0);
        symbolsSpinner.setValue(200.0);
        qamBitsSpinner.setValue(2);            // QPSK
        rollOffSpinner.setValue(0.5);
        filterSpanSpinner.setValue(8);
        correlationThresholdSpinner.setValue(0.60);
        updateQamOrderLabel();
        applyConfigFromUi(false);
        statusLabel.setText("Acoustic preset applied (QPSK, 200 bd, 1500 Hz carrier). "
                + "Apply the SAME settings on the other station.");
    }

    /** (Re)fills the input and output device lists. */
    private void populateDevices() {

        applyConfigFromUi(false);
        inputDeviceCombo.removeAllItems();
        outputDeviceCombo.removeAllItems();

        inputDeviceCombo.addItem(new DeviceItem(null));
        for (Mixer.Info info : audioEngine.listInputDevices()) {
            inputDeviceCombo.addItem(new DeviceItem(info));
        }

        outputDeviceCombo.addItem(new DeviceItem(null));
        for (Mixer.Info info : audioEngine.listOutputDevices()) {
            outputDeviceCombo.addItem(new DeviceItem(info));
        }

        inputDeviceCombo.setSelectedIndex(0);
        outputDeviceCombo.setSelectedIndex(0);
        statusLabel.setText("Audio devices detected: "
                + (inputDeviceCombo.getItemCount() - 1) + " input(s), "
                + (outputDeviceCombo.getItemCount() - 1) + " output(s).");
    }

    /** List item wrapping a Mixer.Info (null = default device). */
    private static final class DeviceItem {
        private final Mixer.Info info;

        DeviceItem(Mixer.Info info) {
            this.info = info;
        }

        @Override
        public String toString() {
            return info == null ? "(Default)" : info.getName();
        }
    }
}
