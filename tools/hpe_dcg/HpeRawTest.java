package org.jc.hpe;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.Arrays;

public final class HpeRawTest {
    interface Check { void run() throws Exception; }
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
    private static void rejects(Check check) throws Exception {
        try { check.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Accepted invalid input");
    }

    public static void main(String[] args) throws Exception {
        require(HpeRaw.matches(1f, 1.005f));
        require(!HpeRaw.matches(1f, 1.1f));
        require(!HpeRaw.matches(Float.NaN, 0f));
        require(!HpeRaw.matches(0f, Float.POSITIVE_INFINITY));
        require(!HpeRaw.matches(-1f, -1f));
        byte[] raw = new byte[8160 * 3072];
        for (int i = 0; i < raw.length; i += 2) { raw[i] = 0; raw[i + 1] = 4; }
        raw[0] = (byte)254; raw[1] = 63;
        int[] stats = HpeRaw.check(raw, 4080, 3072, 8160, 2);
        require(stats[0] == 1024 && stats[1] == 16382 && stats[2] == 0);
        rejects(() -> HpeRaw.check(raw, 4080, 3072, 8160, 1));
        raw[0] = 0; raw[1] = 64;
        rejects(() -> HpeRaw.check(raw, 4080, 3072, 8160, 2));
        raw[1] = 4;
        Arrays.fill(raw, 8160, 16320, (byte)0);
        rejects(() -> HpeRaw.check(raw, 4080, 3072, 8160, 2));
        ByteBuffer tiff = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        tiff.putShort((short)0x4949).putShort((short)42).putInt(8).putShort((short)1);
        tiff.putShort((short)50717).putShort((short)4).putInt(1).putInt(1023).putInt(0);
        Path tmp = Files.createTempFile("hpe-white-", ".dng");
        try {
            byte[] before = tiff.array();
            Files.write(tmp, before);
            HpeRaw.whiteLevel(tmp.toFile());
            byte[] after = Files.readAllBytes(tmp);
            require(ByteBuffer.wrap(after).order(ByteOrder.LITTLE_ENDIAN).getInt(18) == 16383);
            for (int i = 0; i < before.length; i++)
                if (i < 18 || i >= 22) require(before[i] == after[i]);
            rejects(() -> HpeRaw.whiteLevel(tmp.toFile()));
            require(Arrays.equals(after, Files.readAllBytes(tmp)));
        } finally { Files.deleteIfExists(tmp); }
        System.out.println("OK");
    }
}
