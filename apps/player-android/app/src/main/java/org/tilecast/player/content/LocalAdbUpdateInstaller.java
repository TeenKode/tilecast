package org.tilecast.player.content;

import android.content.Context;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explicitly opted-in, loopback-only installer for older Android TV firmware.
 *
 * Only the already verified Tilecast self-update APK reaches this class.
 * ADB access is intentionally limited to a fixed upload location and fixed
 * package-manager operations. No arbitrary shell or remote-host API is exposed.
 *
 * Some CVTE/YaOS firmware permits the same unauthenticated local ADB transport
 * already used by LocalCvteAdb. Authenticated ADB is NOT bypassed: on AUTH the
 * caller must fall back to Android's normal confirmation flow.
 */
public final class LocalAdbUpdateInstaller {
    private static final String PREFS = "tilecast-local-adb-updates";
    private static final String ENABLED = "enabled";
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541;
    private static final int OPEN = 0x4e45504f, OKAY = 0x59414b4f;
    private static final int WRTE = 0x45545257, CLSE = 0x45534c43;
    private static final int MAX_PACKET = 1024 * 1024;
    private static final int MAX_SHELL_OUTPUT = 8192;
    private static final String REMOTE_APK = "/data/local/tmp/tilecast-player-update.apk";
    private static final Pattern SESSION = Pattern.compile("^Success: created install session \\[([0-9]+)\\]\\s*$");

    private LocalAdbUpdateInstaller() {}

    public static boolean supported(int sdk) { return sdk >= 23 && sdk < Build.VERSION_CODES.S; }

    public static boolean enabled(Context context) {
        return supported(Build.VERSION.SDK_INT) &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
    }

    /** This control is only exposed inside the PIN-protected local maintenance menu. */
    public static void setEnabled(Context context, boolean value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, value).apply();
    }

    /**
     * Called on Dispatchers.IO, after the caller's hash, package-name, version
     * and signing-certificate checks and immediately before installation.
     * pm install-commit is executed by the ADB shell (a separate process).
     */
    public static void install(File verifiedApk) throws IOException {
        if (!verifiedApk.isFile() || verifiedApk.length() <= 0)
            throw new IOException("local_adb_apk_missing");
        long size = verifiedApk.length();
        try {
            push(verifiedApk);
            String created = shell("pm install-create -r -S " + size);
            Matcher match = SESSION.matcher(created.trim());
            if (!match.matches()) throw new IOException("local_adb_session_create_failed");
            String id = match.group(1);
            boolean written = false;
            try {
                String write = shell("pm install-write -S " + size + " " + id
                    + " base.apk " + REMOTE_APK);
                if (!write.trim().startsWith("Success"))
                    throw new IOException("local_adb_session_write_failed");
                written = true;
            } finally {
                // install-write copies the bytes into the system session;
                // the temporary file is no longer needed before commit.
                cleanup();
                if (!written) {
                    try { shell("pm install-abandon " + id); } catch (IOException ignored) {}
                }
            }
            // A successful self-update can kill this process before the shell
            // returns. The new player confirms the version after relaunch.
            String committed = shell("pm install-commit " + id);
            if (!committed.trim().startsWith("Success"))
                throw new IOException("local_adb_commit_failed");
        } finally {
            cleanup();
        }
    }

    private static void cleanup() {
        try { shell("rm -f " + REMOTE_APK); } catch (IOException ignored) {}
    }

    private static void push(File file) throws IOException {
        try (Wire wire = new Wire()) {
            wire.open("sync:");
            wire.write(syncFrame("SEND", (REMOTE_APK + ",33152").getBytes(StandardCharsets.UTF_8)));
            byte[] block = new byte[32 * 1024];
            try (FileInputStream input = new FileInputStream(file)) {
                for (int count; (count = input.read(block)) >= 0;) {
                    if (count == 0) continue;
                    wire.write(syncFrame("DATA", block, count));
                }
            }
            wire.write(syncHeader("DONE", (int) (System.currentTimeMillis() / 1000)));
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            while (response.size() < 8) {
                byte[] bytes = wire.readData();
                if (bytes == null) throw new IOException("local_adb_sync_closed");
                if (response.size() + bytes.length > 4096)
                    throw new IOException("local_adb_sync_oversize");
                response.write(bytes);
            }
            byte[] result = response.toByteArray();
            String status = new String(result, 0, 4, StandardCharsets.US_ASCII);
            if (!"OKAY".equals(status)) throw new IOException("local_adb_sync_failed");
            wire.write(syncHeader("QUIT", 0));
        }
    }

    private static String shell(String command) throws IOException {
        try (Wire wire = new Wire()) {
            wire.open("shell:" + command);
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            for (;;) {
                byte[] next = wire.readData();
                if (next == null) break;
                if (data.size() + next.length > MAX_SHELL_OUTPUT)
                    throw new IOException("local_adb_shell_oversize");
                data.write(next);
            }
            return data.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static byte[] syncHeader(String kind, int value) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(kind.getBytes(StandardCharsets.US_ASCII)).putInt(value).array();
    }

    private static byte[] syncFrame(String kind, byte[] value) {
        return syncFrame(kind, value, value.length);
    }

    private static byte[] syncFrame(String kind, byte[] value, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(kind.getBytes(StandardCharsets.US_ASCII)).putInt(length).put(value, 0, length);
        return buffer.array();
    }

    private static final class Wire implements Closeable {
        final Socket socket;
        final DataInputStream in;
        final OutputStream out;
        int remoteId = 0;
        int maxPayload = 4096;
        boolean streamOpen = false;

        Wire() throws IOException {
            socket = new Socket();
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", 5555), 1200);
                socket.setSoTimeout(30000);
                in = new DataInputStream(socket.getInputStream());
                out = socket.getOutputStream();
                send(CNXN, 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.UTF_8));
                Packet hello = read();
                if (hello.command == AUTH) throw new IOException("local_adb_authorization_required");
                if (hello.command != CNXN || hello.arg1 <= 0)
                    throw new IOException("local_adb_handshake_failed");
                maxPayload = Math.min(4096, hello.arg1);
            } catch (IOException error) {
                try { socket.close(); } catch (IOException ignored) {}
                throw error;
            }
        }

        void open(String destination) throws IOException {
            if (streamOpen) throw new IOException("local_adb_stream_already_open");
            send(OPEN, 1, 0, (destination + "\0").getBytes(StandardCharsets.UTF_8));
            Packet result = read();
            if (result.command != OKAY || result.arg1 != 1 || result.arg0 <= 0)
                throw new IOException("local_adb_stream_open_failed");
            remoteId = result.arg0;
            streamOpen = true;
        }

        void write(byte[] data) throws IOException {
            if (!streamOpen) throw new IOException("local_adb_stream_closed");
            for (int offset = 0; offset < data.length;) {
                int length = Math.min(maxPayload, data.length - offset);
                byte[] block = new byte[length];
                System.arraycopy(data, offset, block, 0, length);
                send(WRTE, 1, remoteId, block);
                Packet ack = read();
                if (ack.command != OKAY || ack.arg0 != remoteId || ack.arg1 != 1)
                    throw new IOException("local_adb_write_not_acknowledged");
                offset += length;
            }
        }

        byte[] readData() throws IOException {
            if (!streamOpen) throw new IOException("local_adb_stream_closed");
            Packet packet = read();
            if (packet.command == CLSE && packet.arg1 == 1 && packet.arg0 == remoteId) {
                streamOpen = false;
                send(CLSE, 1, remoteId, new byte[0]);
                return null;
            }
            if (packet.command != WRTE || packet.arg0 != remoteId || packet.arg1 != 1)
                throw new IOException("local_adb_unexpected_stream_packet");
            send(OKAY, 1, remoteId, new byte[0]);
            return packet.data;
        }

        void send(int command, int arg0, int arg1, byte[] data) throws IOException {
            int checksum = 0;
            for (byte b : data) checksum += b & 255;
            ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(data.length)
                .putInt(checksum).putInt(command ^ -1);
            out.write(header.array());
            out.write(data);
            out.flush();
        }

        Packet read() throws IOException {
            byte[] header = new byte[24];
            try { in.readFully(header); } catch (EOFException error) {
                throw new IOException("local_adb_connection_closed", error);
            }
            ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int command = fields.getInt(), arg0 = fields.getInt(), arg1 = fields.getInt();
            int length = fields.getInt(), checksum = fields.getInt(), magic = fields.getInt();
            if (magic != (command ^ -1) || length < 0 || length > MAX_PACKET)
                throw new IOException("local_adb_invalid_header");
            byte[] data = new byte[length];
            in.readFully(data);
            int actual = 0;
            for (byte b : data) actual += b & 255;
            if (actual != checksum) throw new IOException("local_adb_invalid_checksum");
            return new Packet(command, arg0, arg1, data);
        }

        @Override public void close() {
            if (streamOpen) try { send(CLSE, 1, remoteId, new byte[0]); }
                catch (IOException ignored) {}
            try { socket.close(); } catch (IOException ignored) {}
        }
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
