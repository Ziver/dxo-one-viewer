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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.usb4java.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Controller class for the DxO One camera.
 * Handles low-level USB communication, JSON-RPC command framing,
 * and the Live View JPEG stream reconstruction.
 */
public class DxOController {
    /** DxO Vendor ID (0x2b8f) */
    private static final short VENDOR_ID = (short) 0x2b8f;

    /** Signature required by the camera to acknowledge control messages. */
    private static final byte[] METADATA_INIT_RESPONSE_SIGNATURE = hexToBytes("A3BAD110DCBADCBA000000000000000000000000000000000000000000000000");

    /** Standard header for JSON-RPC messages wrapped in the DxO USB protocol. */
    private static final byte[] RPC_HEADER = hexToBytes("A3BAD1101708000C");

    /** Trailer added after the message size in the RPC header. */
    private static final byte[] RPC_HEADER_TRAILER = hexToBytes("00000300000000000000000000000000000000000000");

    private Context context;
    private DeviceHandle handle;
    private final int interfaceNumber0 = 0;
    private final int interfaceNumber1 = 1;
    private byte inEndpoint = (byte) 0x81;
    private byte outEndpoint = (byte) 0x01;
    private int seq = 0;
    private final Gson gson = new Gson();
    private LiveViewListener liveViewListener;
    
    /** Lock to ensure only one thread uses the USB endpoints at a time (RPC vs Live View). */
    private final java.util.concurrent.locks.ReentrantLock usbLock = new java.util.concurrent.locks.ReentrantLock();
    private volatile boolean running = false;
    private Thread liveViewThread;

    /**
     * Interface for receiving live view image frames.
     */
    public interface LiveViewListener {
        /**
         * Called when a complete JPEG frame is reconstructed from the USB stream.
         * @param jpegData The raw bytes of the JPEG image.
         */
        void onFrameReceived(byte[] jpegData);
    }

    /**
     * Sets the listener for live view frames.
     * @param listener The listener implementation.
     */
    public void setLiveViewListener(LiveViewListener listener) {
        this.liveViewListener = listener;
    }

    /**
     * Initializes the USB library, finds the DxO One, and establishes a control connection.
     * Includes interface claiming and the initial protocol handshake.
     * @throws Exception If the device is not found or connection fails.
     */
    public void connect() throws Exception {
        System.out.println("Initializing LibUsb...");
        context = new Context();
        int result = LibUsb.init(context);
        if (result != LibUsb.SUCCESS) throw new LibUsbException("Unable to initialize libusb.", result);

        System.out.println("Searching for DxO One (Vendor ID: 0x2b8f)...");
        Device device = findDevice(VENDOR_ID);
        if (device == null) throw new Exception("DxO One device not found. Ensure it is connected via USB and in the correct mode (autoexec.ash).");

        System.out.println("Opening device handle...");
        handle = new DeviceHandle();
        result = LibUsb.open(device, handle);
        if (result != LibUsb.SUCCESS) {
            LibUsb.unrefDevice(device);
            throw new LibUsbException("Unable to open device.", result);
        }

        try {
            System.out.println("Discovering endpoints...");
            discoverEndpoints(device);
        } finally {
            // Decouple from the device object once endpoints are known and handle is open
            LibUsb.unrefDevice(device);
        }

        System.out.println("Setting configuration 1...");
        result = LibUsb.setConfiguration(handle, 1);
        if (result != LibUsb.SUCCESS && result != LibUsb.ERROR_BUSY) {
            System.err.println("Warning: Could not set configuration: " + LibUsb.errorName(result));
        }

        System.out.println("Claiming interface 0...");
        tryDetach(interfaceNumber0);
        result = LibUsb.claimInterface(handle, interfaceNumber0);
        if (result != LibUsb.SUCCESS) throw new LibUsbException("Unable to claim interface 0.", result);

        System.out.println("Claiming interface 1...");
        tryDetach(interfaceNumber1);
        result = LibUsb.claimInterface(handle, interfaceNumber1);
        if (result != LibUsb.SUCCESS) throw new LibUsbException("Unable to claim interface 1.", result);

        System.out.println("Setting alt setting 1 for interface 1...");
        result = LibUsb.setInterfaceAltSetting(handle, 1, 1);
        if (result != LibUsb.SUCCESS) throw new LibUsbException("Unable to set alt setting.", result);

        System.out.println("Performing initial handshake...");
        transferOut(METADATA_INIT_RESPONSE_SIGNATURE);
        
        System.out.println("Switching camera to 'view' mode...");
        JsonObject modeParams = new JsonObject();
        modeParams.addProperty("param", "view");
        sendRPC("dxo_camera_mode_switch", modeParams);

        System.out.println("Connection successful!");
    }

    /**
     * Scans the device descriptor to find the actual bulk IN and OUT endpoints.
     */
    private void discoverEndpoints(Device device) {
        ConfigDescriptor config = new ConfigDescriptor();
        int result = LibUsb.getConfigDescriptor(device, (byte) 0, config);
        if (result != LibUsb.SUCCESS) return;
        try {
            Interface iface = config.iface()[0];
            InterfaceDescriptor desc = iface.altsetting()[0];
            for (EndpointDescriptor ed : desc.endpoint()) {
                if ((ed.bEndpointAddress() & LibUsb.ENDPOINT_IN) != 0) {
                    inEndpoint = ed.bEndpointAddress();
                    System.out.println("Found IN endpoint: " + String.format("0x%02X", inEndpoint));
                } else {
                    outEndpoint = ed.bEndpointAddress();
                    System.out.println("Found OUT endpoint: " + String.format("0x%02X", outEndpoint));
                }
            }
        } finally {
            LibUsb.freeConfigDescriptor(config);
        }
    }

    /**
     * Attempts to detach the kernel driver (Linux only). Fails silently on other platforms.
     */
    private void tryDetach(int iface) {
        int result = LibUsb.detachKernelDriver(handle, iface);
        if (result != LibUsb.SUCCESS && 
            result != LibUsb.ERROR_NOT_FOUND && 
            result != LibUsb.ERROR_NOT_SUPPORTED) {
            System.err.println("Warning: Could not detach kernel driver: " + LibUsb.errorName(result));
        }
    }

    /**
     * Searches for a device with the specified Vendor ID in the current LibUsb context.
     */
    private Device findDevice(short vendorId) {
        DeviceList list = new DeviceList();
        int result = LibUsb.getDeviceList(context, list);
        if (result < 0) throw new LibUsbException("Unable to get device list", result);

        try {
            for (Device device : list) {
                DeviceDescriptor descriptor = new DeviceDescriptor();
                result = LibUsb.getDeviceDescriptor(device, descriptor);
                if (result != LibUsb.SUCCESS) continue;
                if (descriptor.idVendor() == vendorId) {
                    LibUsb.refDevice(device); 
                    return device;
                }
            }
        } finally {
            LibUsb.freeDeviceList(list, true);
        }
        return null;
    }

    /**
     * Sends a JSON-RPC command to the camera and waits for a response.
     * This method is thread-safe and will block the Live View stream briefly.
     * @param method The RPC method name (e.g., "dxo_setting_set").
     * @param params The parameters for the command.
     * @return The JSON response from the camera.
     * @throws Exception If the USB transfer fails.
     */
    public JsonObject sendRPC(String method, JsonObject params) throws Exception {
        usbLock.lock();
        try {
            // Every command must be preceded by the handshake signature
            transferOut(METADATA_INIT_RESPONSE_SIGNATURE);

            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("id", seq++);
            request.addProperty("method", method);
            if (params != null) {
                request.add("params", params);
            }

            String json = gson.toJson(request) + "\0";
            byte[] payload = json.getBytes();

            // Construct the DxO protocol header: [RPC_HEADER][SIZE_LE][TRAILER][JSON_PAYLOAD]
            ByteBuffer buffer = ByteBuffer.allocateDirect(32 + payload.length);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.put(RPC_HEADER);
            buffer.putShort((short) payload.length);
            buffer.put(RPC_HEADER_TRAILER);
            buffer.put(payload);

            transferOut(buffer);
            return receiveRPC();
        } finally {
            usbLock.unlock();
        }
    }

    /**
     * Reads a response from the bulk IN endpoint and parses it as JSON.
     * Handles potential re-initialization requests from the camera.
     */
    private JsonObject receiveRPC() throws Exception {
        byte[] metadata = transferIn(512);
        if (metadata.length < 32) return null;

        // Camera might request a handshake mid-stream
        if (metadata[0] == (byte)0xA3 && metadata[1] == (byte)0xBA && metadata[4] == (byte)0xAB) {
            System.out.println("Camera requested re-init during RPC reception.");
            transferOut(METADATA_INIT_RESPONSE_SIGNATURE);
            metadata = transferIn(512);
        }

        int size = (metadata[8] & 0xFF) | ((metadata[9] & 0xFF) << 8);
        if (size <= 0 || size > 65535) {
            return null; // Invalid or empty message
        }

        ByteBuffer rpcBuffer = ByteBuffer.allocate(size);
        int fromMetadata = Math.min(size, metadata.length - 32);
        if (fromMetadata > 0) {
            rpcBuffer.put(metadata, 32, fromMetadata);
        }
        
        // Read remaining chunks if JSON was larger than one packet
        int received = fromMetadata;
        while (received < size) {
            byte[] chunk = transferIn(512);
            int toCopy = Math.min(chunk.length, size - received);
            rpcBuffer.put(chunk, 0, toCopy);
            received += toCopy;
        }

        String raw = new String(rpcBuffer.array()).trim();
        
        // Clean up any trailing nulls or corrupted bytes by finding the last brace
        int lastBrace = raw.lastIndexOf('}');
        if (lastBrace != -1) {
            raw = raw.substring(0, lastBrace + 1);
        }

        try {
            return gson.fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            return null; // Corrupted JSON
        }
    }

    /**
     * Starts a background thread that continuously polls the USB port for JPEG data.
     * Reconstructs image frames and sends them to the registered LiveViewListener.
     */
    public void startLiveViewLoop() {
        running = true;
        liveViewThread = new Thread(() -> {
            System.out.println("Live View thread started.");
            byte[] frameBuffer = new byte[1024 * 1024]; 
            int framePos = 0;
            boolean receivingFrame = false;

            while (running && handle != null) {
                // Yield to RPC commands if they need the USB bus
                if (!usbLock.tryLock()) {
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                    continue; 
                }
                try {
                    byte[] data = transferIn(512);
                    if (data.length == 0) continue;

                    // DxO uses a 32-byte header even for image chunks
                    if (data.length >= 4 && data[0] == (byte)0xA3 && data[1] == (byte)0xBA) {
                        if (data[4] == (byte)0x17) {
                            continue; // This is a stray RPC response, ignore in live view loop
                        }
                        
                        // Check for JPEG Start of Image (SOI) marker
                        if (data.length > 33 && data[32] == (byte)0xFF && data[33] == (byte)0xD8) {
                            receivingFrame = true;
                            framePos = 0;
                            int toCopy = data.length - 32;
                            System.arraycopy(data, 32, frameBuffer, framePos, toCopy);
                            framePos += toCopy;
                        }
                    } else if (receivingFrame) {
                        if (framePos + data.length < frameBuffer.length) {
                            System.arraycopy(data, 0, frameBuffer, framePos, data.length);
                            framePos += data.length;
                            
                            // Check for JPEG End of Image (EOI) marker
                            if (data.length >= 2 && data[data.length-2] == (byte)0xFF && data[data.length-1] == (byte)0xD9) {
                                if (liveViewListener != null) {
                                    byte[] finalFrame = new byte[framePos];
                                    System.arraycopy(frameBuffer, 0, finalFrame, 0, framePos);
                                    liveViewListener.onFrameReceived(finalFrame);
                                }
                                receivingFrame = false;
                            }
                        } else {
                            receivingFrame = false; // Overflow
                        }
                    }
                } catch (Exception e) {
                    if (!running) break;
                } finally {
                    usbLock.unlock();
                }
            }
            System.out.println("Live View thread terminated.");
        });
        liveViewThread.start();
    }

    private void transferOut(byte[] data) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocateDirect(data.length);
        buffer.put(data);
        transferOut(buffer);
    }

    /**
     * Low-level bulk transfer to the camera's OUT endpoint.
     */
    private void transferOut(ByteBuffer buffer) throws Exception {
        buffer.rewind();
        IntBuffer transferred = IntBuffer.allocate(1);
        int result = LibUsb.bulkTransfer(handle, outEndpoint, buffer, transferred, 5000);
        if (result != LibUsb.SUCCESS) throw new LibUsbException("Transfer out failed", result);
    }

    /**
     * Low-level bulk transfer from the camera's IN endpoint.
     */
    private byte[] transferIn(int length) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocateDirect(length);
        IntBuffer transferred = IntBuffer.allocate(1);
        int result = LibUsb.bulkTransfer(handle, inEndpoint, buffer, transferred, 5000);
        if (result != LibUsb.SUCCESS) throw new LibUsbException("Transfer in failed", result);
        byte[] data = new byte[transferred.get(0)];
        buffer.get(data);
        return data;
    }

    /**
     * Helper to convert a hex string to a byte array for protocol signatures.
     */
    private static byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    /**
     * Shuts down the connection, releases interfaces, and cleans up LibUsb resources.
     * Safely terminates the Live View thread before closing the handle.
     */
    public void close() {
        System.out.println("Closing DxOController...");
        running = false;
        
        if (liveViewThread != null) {
            try {
                liveViewThread.join(1000); 
            } catch (InterruptedException e) {
                System.err.println("Warning: Live view thread join interrupted");
            }
            liveViewThread = null;
        }

        if (handle != null) {
            try {
                LibUsb.releaseInterface(handle, interfaceNumber0);
                LibUsb.releaseInterface(handle, interfaceNumber1);
                LibUsb.close(handle);
            } catch (Exception e) {
                System.err.println("Warning: Error closing USB handle: " + e.getMessage());
            }
            handle = null;
        }
        if (context != null) {
            try {
                LibUsb.exit(context);
            } catch (Exception e) {
                System.err.println("Warning: Error exiting LibUsb: " + e.getMessage());
            }
            context = null;
        }
        System.out.println("DxOController closed.");
    }
}
