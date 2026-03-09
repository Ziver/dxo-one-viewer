/*
 * Copyright (C) 2026 Ziver Koc
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.dxo.viewer;

import com.formdev.flatlaf.FlatDarkLaf;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

public class DxOViewerApp {
    static {
        System.setProperty("flatlaf.nativeLibraries", "false");
        System.setProperty("flatlaf.useWindowDecorations", "false");
    }

    private static class SettingItem {
        String display, value;
        SettingItem(String d, String v) { this.display = d; this.value = v; }
        @Override public String toString() { return display; }
    }

    private SettingItem si(String d, String v) { return new SettingItem(d, v); }

    private DxOController controller;
    private JFrame frame;
    private JLabel statusLabel;
    private JLabel imageLabel;
    private boolean isConnected = false;
    private boolean isRecording = false;

    private JComboBox<SettingItem> isoBox, apertureBox, exposureBox, evBiasBox;
    private JComboBox<SettingItem> focusModeBox, afModeBox, videoFocusBox, shootingModeBox, driveBox, timerBox;
    private JComboBox<SettingItem> rawBox, tnrBox, wbModeBox, wbIntensityBox, meteringBox, frequencyBox;
    private JComboBox<SettingItem> photoQualityBox, videoQualityBox, maxIsoBox, maxShutterBox, zoomBox;
    private JTextField artistField, copyrightField;
    private JButton applyButton, takePhotoButton, recordVideoButton, connectButton;

    private Map<String, JComboBox<SettingItem>> comboBoxMap = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean isUpdatingImage = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean captureNextFrame = new java.util.concurrent.atomic.AtomicBoolean(false);

    public DxOViewerApp() {
        controller = new DxOController();
        controller.setLiveViewListener(this::updateImage);
        controller.setRpcNotificationListener(this::handleNotification);
        createUI();
    }

    private void handleNotification(JsonObject n) {
        if (!n.has("method")) return;
        String method = n.get("method").getAsString();
        
        if ("dxo_photo_taken".equals(method) || "dxo_shutter_released".equals(method)) {
            SwingUtilities.invokeLater(() -> statusLabel.setText("Status: Shutter released! Waiting for photo data..."));
            captureNextFrame.set(true);
        } else if ("dxo_video_recording_started".equals(method)) {
            isRecording = true;
            SwingUtilities.invokeLater(() -> {
                recordVideoButton.setText("Stop Recording");
                recordVideoButton.setBackground(new Color(200, 0, 0));
                statusLabel.setText("Status: RECORDING VIDEO");
            });
        } else if ("dxo_video_recording_completed".equals(method)) {
            isRecording = false;
            SwingUtilities.invokeLater(() -> {
                recordVideoButton.setText("Record Video");
                recordVideoButton.setBackground(null);
                statusLabel.setText("Status: Video saved to SD card");
            });
        }
    }

    private void createUI() {
        frame = new JFrame("DxO One Settings Manager");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1200, 900);
        frame.setLayout(new BorderLayout());

        JPanel westPanel = new JPanel(new BorderLayout());
        westPanel.setPreferredSize(new Dimension(340, 900));
        westPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(60, 60, 60)));

        JPanel settingsContainer = new JPanel();
        settingsContainer.setLayout(new BoxLayout(settingsContainer, BoxLayout.Y_AXIS));
        settingsContainer.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        addSectionLabel(settingsContainer, "EXPOSURE");
        SettingItem[] isoItems = {si("Auto", "auto"), si("ISO 100", "iso100"), si("ISO 200", "iso200"), si("ISO 400", "iso400"), si("ISO 800", "iso800"), si("ISO 1600", "iso1600"), si("ISO 3200", "iso3200"), si("ISO 6400", "iso6400"), si("ISO 12800", "iso12800"), si("ISO 25600", "iso25600"), si("ISO 51200", "iso51200")};
        addControl(settingsContainer, "ISO", isoBox = createCombo("iso", isoItems));
        SettingItem[] apItems = {si("f/1.8", "1.8"), si("f/2.0", "2.0"), si("f/2.2", "2.2"), si("f/2.5", "2.5"), si("f/2.8", "2.8"), si("f/3.2", "3.2"), si("f/3.5", "3.5"), si("f/4.0", "4.0"), si("f/4.5", "4.5"), si("f/5.0", "5.0"), si("f/5.6", "5.6"), si("f/6.3", "6.3"), si("f/7.1", "7.1"), si("f/8.0", "8.0"), si("f/9.0", "9.0"), si("f/10", "10"), si("f/11", "11")};
        addControl(settingsContainer, "Aperture", apertureBox = createCombo("aperture", apItems));
        SettingItem[] expItems = {si("1/8000s", "1/8000"), si("1/4000s", "1/4000"), si("1/2000s", "1/2000"), si("1/1000s", "1/1000"), si("1/500s", "1/500"), si("1/250s", "1/250"), si("1/125s", "1/125"), si("1/60s", "1/60"), si("1/30s", "1/30"), si("1/15s", "1/15"), si("1/8s", "1/8"), si("1/4s", "1/4"), si("1/2s", "1/2"), si("1s", "1/1"), si("2s", "2/1"), si("4s", "4/1"), si("8s", "8/1"), si("15s", "15/1"), si("30s", "30/1")};
        addControl(settingsContainer, "Exposure Time", exposureBox = createCombo("exposure_time", expItems));
        addControl(settingsContainer, "EV Bias", evBiasBox = createCombo("ev_bias", new SettingItem[]{si("-3.0 EV", "-3.0"), si("-2.0 EV", "-2.0"), si("-1.0 EV", "-1.0"), si("0 EV", "0"), si("+1.0 EV", "+1.0"), si("+2.0 EV", "+2.0"), si("+3.0 EV", "+3.0")}));
        addControl(settingsContainer, "Metering", meteringBox = createCombo("metering", new SettingItem[]{si("Matrix", "matrix"), si("Center", "center"), si("Spot", "spot")}));

        addSectionLabel(settingsContainer, "FOCUS & SHOOTING");
        addControl(settingsContainer, "Focus Mode", focusModeBox = createCombo("still_focusing_mode", new SettingItem[]{si("Auto Focus", "af"), si("Manual Focus", "mf")}));
        addControl(settingsContainer, "AF Mode (Photo)", afModeBox = createCombo("af_mode", new SettingItem[]{si("Single (AF-S)", "af-s"), si("Continuous (AF-C)", "af-c"), si("On-Demand", "af-od")}));
        addControl(settingsContainer, "AF Mode (Video)", videoFocusBox = createCombo("video_focus_mode", new SettingItem[]{si("Continuous", "af-c"), si("Fixed", "fixed")}));
        addControl(settingsContainer, "Shooting Mode", shootingModeBox = createCombo("shooting_mode", new SettingItem[]{si("Program", "program"), si("Aperture Priority", "aperture"), si("Shutter Priority", "shutter"), si("Manual", "manual"), si("Sport", "sport"), si("Portrait", "portrait"), si("Landscape", "landscape"), si("Night", "night")}));
        addControl(settingsContainer, "Drive Mode", driveBox = createCombo("drive", new SettingItem[]{si("Single Shot", "single"), si("Time-Lapse", "timelapse")}));
        addControl(settingsContainer, "Self Timer", timerBox = createCombo("selftimer", new SettingItem[]{si("Off", "0"), si("2 Seconds", "2"), si("10 Seconds", "10")}));

        addSectionLabel(settingsContainer, "IMAGE & VIDEO QUALITY");
        addControl(settingsContainer, "WB Mode", wbModeBox = createCombo("white_balance_mode", new SettingItem[]{si("Auto", "auto"), si("Daylight", "daylight"), si("Cloudy", "cloudy"), si("Tungsten", "tungsten"), si("Fluorescent", "fluorescent")}));
        addControl(settingsContainer, "Auto WB Intensity", wbIntensityBox = createCombo("lighting_intensity", new SettingItem[]{si("Off", "off"), si("Slight", "slight"), si("Medium", "medium"), si("Strong", "strong")}));
        addControl(settingsContainer, "Flicker Reduction", frequencyBox = createCombo("current_frequency", new SettingItem[]{si("50 Hz", "50"), si("60 Hz", "60")}));
        addControl(settingsContainer, "RAW Format", rawBox = createCombo("raw", new SettingItem[]{si("On", "on"), si("Off", "off")}));
        addControl(settingsContainer, "SuperRAW (TNR)", tnrBox = createCombo("tnr", new SettingItem[]{si("On", "on"), si("Off", "off")}));
        addControl(settingsContainer, "Photo Quality", photoQualityBox = createCombo("photo_quality", new SettingItem[]{si("Fine (100%)", "100"), si("Normal (95%)", "95"), si("Basic (70%)", "70")}));
        addControl(settingsContainer, "Video Quality", videoQualityBox = createCombo("video_quality", new SettingItem[]{si("Highest (30 Mbps)", "30000000"), si("Better (22 Mbps)", "22000000"), si("Standard (16 Mbps)", "16000000")}));
        addControl(settingsContainer, "Digital Zoom", zoomBox = createCombo("digital_zoom", new SettingItem[]{si("1.0x", "1.0"), si("1.5x", "1.5"), si("2.0x", "2.0"), si("3.0x", "3.0")}));

        addSectionLabel(settingsContainer, "LIMITS");
        addControl(settingsContainer, "Max ISO", maxIsoBox = createCombo("iso_boundaries", isoItems));
        SettingItem[] maxShutterItems = {si("Auto", "0/1"), si("15s", "15/1"), si("2s", "2/1"), si("1/3s", "1/3"), si("1/6s", "1/6"), si("1/13s", "1/13"), si("1/25s", "1/25"), si("1/50s", "1/50"), si("1/100s", "1/100"), si("1/200s", "1/200"), si("1/400s", "1/400")};
        addControl(settingsContainer, "Max Shutter", maxShutterBox = createCombo("max_exposure", maxShutterItems));

        addSectionLabel(settingsContainer, "METADATA");
        addTextControl(settingsContainer, "Artist Name", artistField = new JTextField());
        addTextControl(settingsContainer, "Copyright Info", copyrightField = new JTextField());

        JScrollPane settingsScroll = new JScrollPane(settingsContainer);
        settingsScroll.getVerticalScrollBar().setUnitIncrement(16);
        settingsScroll.setBorder(null);
        westPanel.add(settingsScroll, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(10, 20, 20, 20));

        applyButton = new JButton("Apply All Settings");
        applyButton.putClientProperty("JButton.buttonType", "roundRect");
        applyButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        applyButton.addActionListener(e -> applyAllSettings());
        buttonPanel.add(applyButton);

        buttonPanel.add(Box.createVerticalStrut(8));
        takePhotoButton = new JButton("Take Photo");
        takePhotoButton.putClientProperty("JButton.buttonType", "roundRect");
        takePhotoButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        takePhotoButton.addActionListener(e -> takePhoto());
        buttonPanel.add(takePhotoButton);

        buttonPanel.add(Box.createVerticalStrut(8));
        recordVideoButton = new JButton("Record Video");
        recordVideoButton.putClientProperty("JButton.buttonType", "roundRect");
        recordVideoButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        recordVideoButton.addActionListener(e -> toggleVideoRecording());
        buttonPanel.add(recordVideoButton);

        buttonPanel.add(Box.createVerticalStrut(15));
        connectButton = new JButton("Connect Camera");
        connectButton.putClientProperty("JButton.buttonType", "roundRect");
        connectButton.setBackground(new Color(60, 120, 220));
        connectButton.setForeground(Color.WHITE);
        connectButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        connectButton.addActionListener(e -> toggleConnection());
        buttonPanel.add(connectButton);

        westPanel.add(buttonPanel, BorderLayout.SOUTH);
        frame.add(westPanel, BorderLayout.WEST);

        imageLabel = new JLabel("No Signal", SwingConstants.CENTER);
        imageLabel.setBackground(new Color(25, 25, 25));
        imageLabel.setOpaque(true);
        imageLabel.setForeground(new Color(100, 100, 100));
        imageLabel.setFont(new Font("SansSerif", Font.BOLD, 24));
        JScrollPane imageScroll = new JScrollPane(imageLabel);
        imageScroll.setBorder(null);
        frame.add(imageScroll, BorderLayout.CENTER);

        statusLabel = new JLabel("Status: Disconnected");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        frame.add(statusLabel, BorderLayout.SOUTH);

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { controller.close(); System.exit(0); }
        });

        setUIEnabled(false);
    }

    private JComboBox<SettingItem> createCombo(String key, SettingItem[] items) {
        JComboBox<SettingItem> combo = new JComboBox<>(items);
        comboBoxMap.put(key, combo);
        return combo;
    }

    private void addSectionLabel(JPanel panel, String text) {
        JLabel l = new JLabel(text);
        l.setForeground(new Color(100, 150, 255));
        l.setFont(new Font("SansSerif", Font.BOLD, 11));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(Box.createVerticalStrut(15));
        panel.add(l);
        panel.add(Box.createVerticalStrut(8));
    }

    private void addControl(JPanel panel, String label, JComponent component) {
        JLabel l = new JLabel(label);
        l.setFont(new Font("SansSerif", Font.PLAIN, 12));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(l);
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));
        panel.add(component);
        panel.add(Box.createVerticalStrut(8));
    }

    private void addTextControl(JPanel panel, String label, JTextField field) {
        addControl(panel, label, field);
    }

    private void setUIEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            for (JComboBox<SettingItem> combo : comboBoxMap.values()) combo.setEnabled(enabled);
            artistField.setEnabled(enabled);
            copyrightField.setEnabled(enabled);
            applyButton.setEnabled(enabled);
            takePhotoButton.setEnabled(enabled);
            recordVideoButton.setEnabled(enabled);
        });
    }

    private void updateImage(byte[] jpegData) {
        if (!isConnected) return;
        if (captureNextFrame.get()) {
            if (jpegData.length > 100000) {
                captureNextFrame.set(false);
                SwingUtilities.invokeLater(() -> showPhotoPreview(jpegData));
                return;
            }
        }
        if (isUpdatingImage.get()) return;
        isUpdatingImage.set(true);
        try {
            Image img = ImageIO.read(new ByteArrayInputStream(jpegData));
            if (img != null) {
                int lblW = imageLabel.getWidth(), lblH = imageLabel.getHeight();
                if (lblW < 50 || lblH < 50) { img.flush(); isUpdatingImage.set(false); return; }
                double ratio = Math.min((double)lblW / img.getWidth(null), (double)lblH / img.getHeight(null));
                Image scaled = img.getScaledInstance((int)(img.getWidth(null)*ratio), (int)(img.getHeight(null)*ratio), Image.SCALE_FAST);
                SwingUtilities.invokeLater(() -> { imageLabel.setIcon(new ImageIcon(scaled)); imageLabel.setText(""); isUpdatingImage.set(false); });
                img.flush();
            } else isUpdatingImage.set(false);
        } catch (Exception e) { isUpdatingImage.set(false); }
    }

    private void connect() {
        statusLabel.setText("Status: Connecting...");
        new Thread(() -> {
            try {
                controller.connect();
                controller.startLiveViewLoop();
                JsonObject allSettings = controller.sendRPC("dxo_all_settings_get", null);
                SwingUtilities.invokeLater(() -> {
                    isConnected = true;
                    connectButton.setText("Disconnect Camera");
                    connectButton.setBackground(new Color(180, 50, 50));
                    if (allSettings != null && allSettings.has("result")) updateUIFromSettings(allSettings.getAsJsonObject("result"));
                    setUIEnabled(true);
                    statusLabel.setText("Status: Connected & Synced");
                });
            } catch (Exception e) {
                e.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Status: Connection Failed");
                    JOptionPane.showMessageDialog(frame, "Error connecting: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    private void disconnect() {
        statusLabel.setText("Status: Disconnecting...");
        isConnected = false;
        imageLabel.setIcon(null);
        imageLabel.setText("No Signal");
        new Thread(() -> {
            try {
                controller.close();
                SwingUtilities.invokeLater(() -> {
                    connectButton.setText("Connect Camera");
                    connectButton.setBackground(new Color(60, 120, 220));
                    setUIEnabled(false);
                    statusLabel.setText("Status: Disconnected");
                });
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private void toggleConnection() { if (isConnected) disconnect(); else connect(); }

    private void updateUIFromSettings(JsonObject settings) {
        SwingUtilities.invokeLater(() -> {
            for (String key : settings.keySet()) {
                if (comboBoxMap.containsKey(key)) {
                    String val = settings.get(key).getAsString().toLowerCase();
                    JComboBox<SettingItem> combo = comboBoxMap.get(key);
                    for (int i = 0; i < combo.getItemCount(); i++) {
                        if (combo.getItemAt(i).value.equalsIgnoreCase(val)) {
                            combo.setSelectedIndex(i);
                            break;
                        }
                    }
                }
            }
            if (settings.has("artist")) artistField.setText(settings.get("artist").getAsString());
            if (settings.has("copyright")) copyrightField.setText(settings.get("copyright").getAsString());
        });
    }

    private void applyAllSettings() {
        statusLabel.setText("Status: Applying all settings...");
        new Thread(() -> {
            try {
                for (Map.Entry<String, JComboBox<SettingItem>> entry : comboBoxMap.entrySet()) {
                    SettingItem item = entry.getValue().getItemAt(entry.getValue().getSelectedIndex());
                    setSetting(entry.getKey(), item.value);
                }
                setSetting("artist", artistField.getText());
                setSetting("copyright", copyrightField.getText());
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Status: Connected & Synced");
                    JOptionPane.showMessageDialog(frame, "All settings applied successfully!");
                });
            } catch (Exception e) {
                e.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Status: Error applying settings");
                    JOptionPane.showMessageDialog(frame, "Error applying settings: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    private void setSetting(String type, String param) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("type", type);
        params.addProperty("param", param);
        controller.sendRPC("dxo_setting_set", params);
    }

    private void takePhoto() {
        statusLabel.setText("Status: Taking photo...");
        new Thread(() -> {
            try {
                controller.sendRPC("dxo_photo_take", null);
                SwingUtilities.invokeLater(() -> statusLabel.setText("Status: Shutter triggered! Waiting for photo data..."));
            } catch (Exception e) {
                e.printStackTrace();
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, "Error taking photo: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
            }
        }).start();
    }

    private void toggleVideoRecording() {
        if (!isRecording) {
            statusLabel.setText("Status: Starting video recording...");
            new Thread(() -> {
                try {
                    JsonObject modeParams = new JsonObject();
                    modeParams.addProperty("param", "video");
                    controller.sendRPC("dxo_camera_mode_switch", modeParams);
                    
                    Thread.sleep(500); 
                    
                    JsonObject response = controller.sendRPC("dxo_video_recording_start", null);
                    if (response != null && response.has("result")) {
                        SwingUtilities.invokeLater(() -> {
                            if (!isRecording) {
                                isRecording = true;
                                recordVideoButton.setText("Stop Recording");
                                recordVideoButton.setBackground(new Color(200, 0, 0));
                                statusLabel.setText("Status: RECORDING VIDEO");
                            }
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Status: Error starting video");
                        JOptionPane.showMessageDialog(frame, "Error starting video: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }).start();
        } else {
            statusLabel.setText("Status: Stopping video recording...");
            new Thread(() -> {
                try {
                    controller.sendRPC("dxo_video_recording_stop", null);
                    JsonObject modeParams = new JsonObject();
                    modeParams.addProperty("param", "view");
                    controller.sendRPC("dxo_camera_mode_switch", modeParams);
                } catch (Exception e) {
                    e.printStackTrace();
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, "Error stopping video: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
                }
            }).start();
        }
    }

    private void showPhotoPreview(byte[] jpegData) {
        try {
            Image img = ImageIO.read(new ByteArrayInputStream(jpegData));
            if (img == null) return;
            JFrame previewFrame = new JFrame("Photo Preview");
            previewFrame.setSize(1000, 800);
            previewFrame.setLayout(new BorderLayout());
            JLabel photoLabel = new JLabel(new ImageIcon(img.getScaledInstance(980, -1, Image.SCALE_SMOOTH)));
            previewFrame.add(new JScrollPane(photoLabel), BorderLayout.CENTER);
            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton saveBtn = new JButton("Save Photo");
            saveBtn.putClientProperty("JButton.buttonType", "roundRect");
            saveBtn.addActionListener(e -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setSelectedFile(new java.io.File("DxO_Capture.jpg"));
                if (chooser.showSaveDialog(previewFrame) == JFileChooser.APPROVE_OPTION) {
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(chooser.getSelectedFile())) {
                        fos.write(jpegData);
                        JOptionPane.showMessageDialog(previewFrame, "Photo saved successfully!");
                    } catch (Exception ex) { JOptionPane.showMessageDialog(previewFrame, "Error saving photo: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE); }
                }
            });
            btnPanel.add(saveBtn);
            previewFrame.add(btnPanel, BorderLayout.SOUTH);
            previewFrame.setLocationRelativeTo(frame);
            previewFrame.setVisible(true);
            previewFrame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { img.flush(); } });
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void show() { frame.setVisible(true); }

    public static void main(String[] args) {
        FlatDarkLaf.setup();
        SwingUtilities.invokeLater(() -> new DxOViewerApp().show());
    }
}
