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
    private static final short VENDOR_ID = (short) 0x2b8f;
    private static final byte[] METADATA_INIT_RESPONSE_SIGNATURE = hexToBytes("A3BAD110DCBADCBA000000000000000000000000000000000000000000000000");
    private static final byte[] RPC_HEADER = hexToBytes("A3BAD1101708000C");
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
    private RpcNotificationListener rpcListener;
    
    private final java.util.concurrent.locks.ReentrantLock usbLock = new java.util.concurrent.locks.ReentrantLock();
    private volatile boolean running = false;
    private Thread liveViewThread;

    public interface LiveViewListener {
        void onFrameReceived(byte[] jpegData);
    }

    public interface RpcNotificationListener {
        void onNotificationReceived(JsonObject notification);
    }

    public void setLiveViewListener(LiveViewListener listener) {
        this.liveViewListener = listener;
    }

    public void setRpcNotificationListener(RpcNotificationListener listener) {
        this.rpcListener = listener;
    }

    public void connect() throws Exception {
        System.out.println("Initializing LibUsb...");
        context = new Context();
        int result = LibUsb.init(context);
        if (result != LibUsb.SUCCESS) throw new LibUsbException("Unable to initialize libusb.", result);

        Device device = findDevice(VENDOR_ID);
        if (device == null) throw new Exception("DxO One device not found.");

        handle = new DeviceHandle();
        result = LibUsb.open(device, handle);
        if (result != LibUsb.SUCCESS) {
            LibUsb.unrefDevice(device);
            throw new LibUsbException("Unable to open device.", result);
        }

        try {
            discoverEndpoints(device);
        } finally {
            LibUsb.unrefDevice(device);
        }

        LibUsb.setConfiguration(handle, 1);
        tryDetach(interfaceNumber0);
        LibUsb.claimInterface(handle, interfaceNumber0);
        tryDetach(interfaceNumber1);
        LibUsb.claimInterface(handle, interfaceNumber1);
        LibUsb.setInterfaceAltSetting(handle, 1, 1);

        transferOut(METADATA_INIT_RESPONSE_SIGNATURE);
        
        JsonObject modeParams = new JsonObject();
        modeParams.addProperty("param", "view");
        sendRPC("dxo_camera_mode_switch", modeParams);

        System.out.println("Connection successful!");
    }

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
                } else {
                    outEndpoint = ed.bEndpointAddress();
                }
            }
        } finally {
            LibUsb.freeConfigDescriptor(config);
        }
    }

    private void tryDetach(int iface) {
        int result = LibUsb.detachKernelDriver(handle, iface);
        if (result != LibUsb.SUCCESS && result != LibUsb.ERROR_NOT_FOUND && result != LibUsb.ERROR_NOT_SUPPORTED) {
            // Ignored
        }
    }

    private Device findDevice(short vendorId) {
        DeviceList list = new DeviceList();
        int result = LibUsb.getDeviceList(context, list);
        if (result < 0) throw new LibUsbException("Unable to get device list", result);
        try {
            for (Device device : list) {
                DeviceDescriptor descriptor = new DeviceDescriptor();
                if (LibUsb.getDeviceDescriptor(device, descriptor) != LibUsb.SUCCESS) continue;
                if (descriptor.idVendor() == vendorId) { LibUsb.refDevice(device); return device; }
            }
        } finally { LibUsb.freeDeviceList(list, true); }
        return null;
    }

    public JsonObject sendRPC(String method, JsonObject params) throws Exception {
        usbLock.lock();
        try {
            transferOut(METADATA_INIT_RESPONSE_SIGNATURE);
            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("id", seq++);
            request.addProperty("method", method);
            if (params != null) request.add("params", params);

            String json = gson.toJson(request) + "\0";
            byte[] payload = json.getBytes();
            ByteBuffer buffer = ByteBuffer.allocateDirect(32 + payload.length);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.put(RPC_HEADER).putShort((short) payload.length).put(RPC_HEADER_TRAILER).put(payload);

            transferOut(buffer);
            JsonObject response = receiveRPC();
            if (response != null) {
                System.out.println("RPC Result (" + method + "): " + response.toString());
            }
            return response;
        } finally { usbLock.unlock(); }
    }

    private JsonObject receiveRPC() throws Exception {
        byte[] metadata = transferIn(512, 5000);
        if (metadata.length < 32) return null;

        if (metadata[0] == (byte)0xA3 && metadata[1] == (byte)0xBA && metadata[4] == (byte)0xAB) {
            transferOut(METADATA_INIT_RESPONSE_SIGNATURE);
            metadata = transferIn(512, 5000);
        }

        int size = (metadata[8] & 0xFF) | ((metadata[9] & 0xFF) << 8);
        if (size <= 0 || size > 65535) return null;

        ByteBuffer rpcBuffer = ByteBuffer.allocate(size);
        int fromMetadata = Math.min(size, metadata.length - 32);
        if (fromMetadata > 0) rpcBuffer.put(metadata, 32, fromMetadata);
        
        int received = fromMetadata;
        while (received < size) {
            byte[] chunk = transferIn(512, 1000);
            int toCopy = Math.min(chunk.length, size - received);
            rpcBuffer.put(chunk, 0, toCopy);
            received += toCopy;
        }

        String raw = new String(rpcBuffer.array()).trim();
        int lastBrace = raw.lastIndexOf('}');
        if (lastBrace != -1) raw = raw.substring(0, lastBrace + 1);

        try {
            JsonObject res = gson.fromJson(raw, JsonObject.class);
            if (res != null && res.has("method") && "dxo_usb_flush_forced".equals(res.get("method").getAsString())) {
                return receiveRPC();
            }
            return res;
        } catch (Exception e) { return null; }
    }

    public void startLiveViewLoop() {
        running = true;
        liveViewThread = new Thread(() -> {
            byte[] frameBuffer = new byte[2 * 1024 * 1024]; 
            int framePos = 0;
            boolean receivingFrame = false;

            while (running && handle != null) {
                if (!usbLock.tryLock()) {
                    try { Thread.sleep(5); } catch (InterruptedException e) { break; }
                    continue; 
                }
                try {
                    byte[] data = transferIn(512, 200);
                    if (data.length == 0) continue;

                    if (data.length >= 4 && data[0] == (byte)0xA3 && data[1] == (byte)0xBA) {
                        if (data[4] == (byte)0x17) {
                            parseAndDispatchRpc(data);
                            continue;
                        }
                        
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
                            
                            if (data.length >= 2 && data[data.length-2] == (byte)0xFF && data[data.length-1] == (byte)0xD9) {
                                if (liveViewListener != null) {
                                    byte[] finalFrame = new byte[framePos];
                                    System.arraycopy(frameBuffer, 0, finalFrame, 0, framePos);
                                    liveViewListener.onFrameReceived(finalFrame);
                                }
                                receivingFrame = false;
                            }
                        } else { receivingFrame = false; }
                    }
                } catch (Exception e) {
                    if (!running) break;
                } finally { usbLock.unlock(); }
            }
        });
        liveViewThread.start();
    }

    private void parseAndDispatchRpc(byte[] firstChunk) {
        try {
            int size = (firstChunk[8] & 0xFF) | ((firstChunk[9] & 0xFF) << 8);
            if (size <= 0) return;

            ByteBuffer rpcBuffer = ByteBuffer.allocate(size);
            int fromFirst = Math.min(size, firstChunk.length - 32);
            if (fromFirst > 0) rpcBuffer.put(firstChunk, 32, fromFirst);

            int received = fromFirst;
            while (received < size) {
                byte[] chunk = transferIn(512, 1000);
                if (chunk.length == 0) break;
                int toCopy = Math.min(chunk.length, size - received);
                rpcBuffer.put(chunk, 0, toCopy);
                received += toCopy;
            }

            String raw = new String(rpcBuffer.array()).trim();
            int lastBrace = raw.lastIndexOf('}');
            if (lastBrace != -1) raw = raw.substring(0, lastBrace + 1);

            JsonObject json = gson.fromJson(raw, JsonObject.class);
            if (json != null) {
                String method = json.has("method") ? json.get("method").getAsString() : "unknown";
                if ("unknown".equals(method) || method.contains("warning")) {
                    System.out.println("Async Info: " + raw);
                } else {
                    System.out.println("Async Notification: " + method);
                }
                if (rpcListener != null) rpcListener.onNotificationReceived(json);
            }
        } catch (Exception e) {
            System.err.println("Error parsing async notification: " + e.getMessage());
        }
    }

    private void transferOut(byte[] data) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocateDirect(data.length);
        buffer.put(data);
        transferOut(buffer);
    }

    private void transferOut(ByteBuffer buffer) throws Exception {
        buffer.rewind();
        IntBuffer transferred = IntBuffer.allocate(1);
        int result = LibUsb.bulkTransfer(handle, outEndpoint, buffer, transferred, 5000);
        if (result != LibUsb.SUCCESS) throw new LibUsbException("Transfer out failed", result);
    }

    private byte[] transferIn(int length, int timeout) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocateDirect(length);
        IntBuffer transferred = IntBuffer.allocate(1);
        int result = LibUsb.bulkTransfer(handle, inEndpoint, buffer, transferred, timeout);
        if (result == LibUsb.ERROR_TIMEOUT) return new byte[0];
        if (result != LibUsb.SUCCESS) throw new LibUsbException("Transfer in failed", result);
        byte[] data = new byte[transferred.get(0)];
        buffer.get(data);
        return data;
    }

    private static byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    public void close() {
        System.out.println("Closing DxOController...");
        running = false;
        if (liveViewThread != null) {
            try { liveViewThread.join(1500); } catch (InterruptedException e) {}
            liveViewThread = null;
        }
        if (handle != null) {
            try {
                LibUsb.releaseInterface(handle, interfaceNumber0);
                LibUsb.releaseInterface(handle, interfaceNumber1);
                LibUsb.close(handle);
            } catch (Exception e) {}
            handle = null;
        }
        if (context != null) {
            try { LibUsb.exit(context); } catch (Exception e) {}
            context = null;
        }
        System.out.println("DxOController closed.");
    }
}
