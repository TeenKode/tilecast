package org.tilecast.player.power;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal, strictly loopback ADB transport for a CVTE firmware that accepts
 * local unauthenticated ADB at 127.0.0.1:5555.
 *
 * Adapted from the user's cvte-root-toolkit sleep-timer integration.
 * No authentication keys, generic shell API or caller-provided commands.
 */
final class LocalCvteAdb {
    private static final int CNXN = 0x4e584e43;
    private static final int OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f;
    private static final int WRTE = 0x45545257;
    private static final int CLSE = 0x45534c43;
    private static final int AUTH = 0x48545541;
    private static final int MAX_PACKET = 1024 * 1024;
    private static final int MAX_OUTPUT = 32768;

    private LocalCvteAdb() {}

    /** No blind toggle. The firmware is the authority for fake standby. */
    static boolean setAwake(boolean awake) throws IOException {
        String expected = awake ? "0" : "1";
        String command =
            "mode=$(/system/bin/settings get global fake_standby_switch); " +
            "state=$(/system/bin/settings get global IS_FAKE_STANDBY_STATUS); " +
            "if [ \"$mode\" != true ]; then echo POWER:UNAVAILABLE; " +
            "elif [ \"$state\" = " + expected + " ]; then echo POWER:OK; " +
            "elif [ \"$state\" = 0 ] || [ \"$state\" = 1 ]; then " +
            "/system/bin/input keyevent 26; sleep 1; " +
            "state=$(/system/bin/settings get global IS_FAKE_STANDBY_STATUS); " +
            "if [ \"$state\" = " + expected + " ]; then echo POWER:OK; else echo POWER:FAILED; fi; " +
            "else echo POWER:UNAVAILABLE; fi";
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 1200);
            socket.setSoTimeout(7000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            send(out, CNXN, 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.UTF_8));
            Packet hello = read(in);
            if (hello.command == AUTH) throw new IOException("ADB requires authorization");
            if (hello.command != CNXN) throw new IOException("Unexpected local ADB handshake");
            send(out, OPEN, 1, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + 9_000_000_000L;
            while (System.nanoTime() < deadline) {
                Packet packet = read(in);
                if (packet.command == OKAY) continue;
                if (packet.command == WRTE) {
                    if (packet.arg1 != 1 || result.size() + packet.data.length > MAX_OUTPUT)
                        throw new IOException("Unexpected ADB stream or excessive output");
                    result.write(packet.data);
                    send(out, OKAY, 1, packet.arg0, new byte[0]);
                } else if (packet.command == CLSE) {
                    if (packet.arg1 != 1) throw new IOException("Unexpected ADB close");
                    send(out, CLSE, 1, packet.arg0, new byte[0]);
                    return result.toString(StandardCharsets.UTF_8.name()).trim().equals("POWER:OK");
                } else throw new IOException("Unexpected ADB response");
            }
            throw new IOException("Local ADB power response timed out");
        }
    }

    private static void send(OutputStream output, int command, int arg0, int arg1, byte[] data) throws IOException {
        int checksum = 0;
        for (byte b : data) checksum += b & 255;
        byte[] header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.length)
            .putInt(checksum).putInt(command ^ -1).array();
        output.write(header);
        output.write(data);
        output.flush();
    }

    private static Packet read(DataInputStream input) throws IOException {
        byte[] header = new byte[24];
        input.readFully(header);
        ByteBuffer parts = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = parts.getInt(), arg0 = parts.getInt(), arg1 = parts.getInt();
        int len = parts.getInt(), checksum = parts.getInt(), magic = parts.getInt();
        if (magic != (command ^ -1) || len < 0 || len > MAX_PACKET)
            throw new IOException("Malformed ADB header");
        byte[] data = new byte[len];
        input.readFully(data);
        int actual = 0;
        for (byte b : data) actual += b & 255;
        if (actual != checksum) throw new IOException("ADB payload checksum mismatch");
        return new Packet(command, arg0, arg1, data);
    }

    private static final class Packet {
        final int command, arg0, arg1;
        final byte[] data;
        Packet(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }
}
