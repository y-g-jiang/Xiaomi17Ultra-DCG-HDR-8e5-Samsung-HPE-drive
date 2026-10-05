package org.jc.hpe;

import java.io.*;

public final class HpeRaw {
    private HpeRaw() {}

    public static boolean matches(float requested, float actual) {
        return Float.isFinite(requested) && Float.isFinite(actual) && requested >= 0 && actual >= 0 &&
                Math.abs(requested - actual) <= Math.max(.01f, requested * .01f);
    }

    public static int[] check(byte[] data, int width, int height, int stride, int pixelStride) throws IOException {
        if (width != 4080 || height != 3072 || stride != 8160 || pixelStride != 2 || data.length != stride * height)
            throw new IOException("HPE RAW geometry mismatch");
        int min = 65535, max = 0, odd = 0;
        for (int row = 0; row < height; row++) {
            boolean present = false;
            for (int at = row * stride; at < (row + 1) * stride; at += 2) {
                int value = (data[at] & 255) | ((data[at + 1] & 255) << 8);
                if (value > 16383) throw new IOException("HPE RAW code out of range");
                min = Math.min(min, value);
                max = Math.max(max, value);
                odd += value & 1;
                present |= value != 0;
            }
            if (!present) throw new IOException("Missing RAW row " + row);
        }
        if (max <= 1023) throw new IOException("DCG code range unconfirmed");
        return new int[]{min, max, odd};
    }

    private static long u32(RandomAccessFile f) throws IOException {
        return (long)f.readUnsignedByte() | ((long)f.readUnsignedByte() << 8) |
                ((long)f.readUnsignedByte() << 16) | ((long)f.readUnsignedByte() << 24);
    }

    private static int u16(RandomAccessFile f) throws IOException {
        return f.readUnsignedByte() | (f.readUnsignedByte() << 8);
    }

    public static void whiteLevel(File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
            if (f.readUnsignedByte() != 73 || f.readUnsignedByte() != 73 || u16(f) != 42)
                throw new IOException("Unsupported DNG TIFF header");
            long ifd = u32(f);
            if (ifd < 8 || ifd >= f.length() - 2) throw new IOException("Invalid DNG IFD");
            f.seek(ifd);
            int count = u16(f);
            for (int i = 0; i < count; i++) {
                long at = ifd + 2L + i * 12;
                if (at > f.length() - 12) throw new IOException("Truncated DNG IFD");
                f.seek(at);
                int tag = u16(f), type = u16(f);
                long n = u32(f), value = u32(f);
                if (tag == 50717) {
                    if (type != 4 || n != 1 || value != 1023)
                        throw new IOException("Unexpected DNG WhiteLevel");
                    f.seek(at + 8);
                    for (int k = 0; k < 4; k++) f.write((16383 >>> (k * 8)) & 255);
                    f.getFD().sync();
                    return;
                }
            }
            throw new IOException("Missing DNG WhiteLevel");
        }
    }
}
