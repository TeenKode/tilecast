package org.tilecast.player.power;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Pure contract tests: real standby requires the physical television. */
public class LocalCvteAdbTest {
    @Test public void fixedCommandsCannotBlindlyTogglePower() {
        String sleep = LocalCvteAdb.fixedCommand(false);
        String wake = LocalCvteAdb.fixedCommand(true);
        for (String command : new String[]{sleep, wake}) {
            assertTrue(command.contains("fake_standby_switch"));
            assertTrue(command.contains("IS_FAKE_STANDBY_STATUS"));
            assertTrue(command.contains("input keyevent 26"));
            assertTrue(command.contains("POWER:UNAVAILABLE"));
            assertFalse(command.contains("keyevent 223"));
        }
        assertTrue(sleep.contains("= 1 ]"));
        assertTrue(wake.contains("= 0 ]"));
    }
    @Test public void onlyExactPowerConfirmationIsAccepted() {
        assertTrue(LocalCvteAdb.acknowledged("POWER:OK\n"));
        assertFalse(LocalCvteAdb.acknowledged("POWER:FAILED\n"));
        assertFalse(LocalCvteAdb.acknowledged("prefix POWER:OK"));
        assertFalse(LocalCvteAdb.acknowledged("POWER:UNAVAILABLE"));
    }

    @Test public void acceptsConfirmedReadbackAcrossPackets() throws IOException {
        assertTrue(exchange(packet("CNXN", 0, 0, "device::"),
            packet("OKAY", 7, 1, ""), packet("WRTE", 7, 1, "POWER:"),
            packet("WRTE", 7, 1, "OK\n"), packet("CLSE", 7, 1, "")));
    }

    @Test public void closedStreamWithoutConfirmationFails() throws IOException {
        assertFalse(exchange(packet("CNXN", 0, 0, "device::"), packet("CLSE", 7, 1, "")));
    }

    @Test public void refusesAuthenticationInsteadOfSendingACommand() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> LocalCvteAdb.exchange(
            new DataInputStream(new ByteArrayInputStream(packet("AUTH", 1, 0, "challenge"))), output, false));
        assertFalse(new String(output.toByteArray(), StandardCharsets.UTF_8).contains("shell:"));
    }

    @Test public void rejectsWrongStreamAndMalformedPayload() {
        assertThrows(IOException.class, () -> exchange(packet("CNXN", 0, 0, ""),
            packet("WRTE", 7, 2, "POWER:OK\n")));
        byte[] corrupt = packet("WRTE", 7, 1, "POWER:OK\n");
        corrupt[24] ^= 1;
        assertThrows(IOException.class, () -> exchange(packet("CNXN", 0, 0, ""), corrupt));
        byte[] oversized = packet("CNXN", 0, 0, "");
        ByteBuffer.wrap(oversized).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 1024 * 1024 + 1);
        assertThrows(IOException.class, () -> exchange(oversized));
    }

    @Test public void rejectsExcessiveOutputAndTruncatedPackets() {
        assertThrows(IOException.class, () -> exchange(packet("CNXN", 0, 0, ""),
            packet("WRTE", 7, 1, "x".repeat(32769))));
        assertThrows(IOException.class, () -> exchange(new byte[12]));
    }

    @Test public void expiredRequestCannotOpenPowerTransport() throws IOException {
        assertThrows(IOException.class, () -> LocalCvteAdb.setAwake(false, 0));
        assertThrows(IOException.class, () -> LocalCvteAdb.timeoutMillis(System.nanoTime() - 1));
        assertTrue(LocalCvteAdb.timeoutMillis(System.nanoTime() + 60_000_000_000L) <= 8000);
    }

    private static boolean exchange(byte[]... packets) throws IOException {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        for (byte[] packet : packets) input.write(packet);
        return LocalCvteAdb.exchange(new DataInputStream(new ByteArrayInputStream(input.toByteArray())),
            new ByteArrayOutputStream(), false);
    }

    private static byte[] packet(String name, int arg0, int arg1, String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int command = ByteBuffer.wrap(name.getBytes(StandardCharsets.US_ASCII))
            .order(ByteOrder.LITTLE_ENDIAN).getInt();
        int checksum = 0;
        for (byte value : payload) checksum += value & 255;
        return ByteBuffer.allocate(24 + payload.length).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(payload.length)
            .putInt(checksum).putInt(command ^ -1).put(payload).array();
    }
}
