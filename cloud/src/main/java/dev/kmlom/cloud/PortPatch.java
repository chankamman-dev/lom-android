package dev.kmlom.cloud;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.zip.GZIPInputStream;

// Rebuilds a file the PC converter changed (serialized files whose shaders went from DXBC to GLES3)
// from the Windows original downloaded from Steam plus a small patch shipped in the APK.
//
// Patch format (gzip): "LPT1", u64 output size, then ops until 0:
//   1 = COPY  u64 source offset, u32 length   (bytes from the Windows original)
//   2 = ADD   u32 length, bytes                (new bytes, mostly the converted shader programs)
// tools/porttool/PortTool.java writes it and checks that this class reproduces the PC output exactly.
public final class PortPatch {
    public static final int COPY = 1, ADD = 2;

    // Writes the patched file to `out` and returns its size.
    public static long apply(RandomAccessFile src, InputStream patchGz, OutputStream out) throws IOException {
        DataInputStream p = new DataInputStream(new BufferedInputStream(new GZIPInputStream(patchGz, 1 << 16), 1 << 16));
        byte[] magic = new byte[4];
        p.readFully(magic);
        if (magic[0] != 'L' || magic[1] != 'P' || magic[2] != 'T' || magic[3] != '1') throw new IOException("补丁格式不对");
        long size = p.readLong(), written = 0;
        byte[] buf = new byte[1 << 17];
        long srcLen = src.length();
        for (int op; (op = p.read()) != 0; ) {
            if (op < 0) throw new EOFException("补丁不完整");
            if (op == COPY) {
                long off = p.readLong();
                long len = p.readInt() & 0xFFFFFFFFL;
                if (off < 0 || off + len > srcLen) throw new IOException("补丁和 Steam 上的原文件对不上");
                src.seek(off);
                while (len > 0) {
                    int n = (int) Math.min(buf.length, len);
                    src.readFully(buf, 0, n);
                    out.write(buf, 0, n);
                    len -= n;
                    written += n;
                }
            } else if (op == ADD) {
                long len = p.readInt() & 0xFFFFFFFFL;
                while (len > 0) {
                    int n = (int) Math.min(buf.length, len);
                    p.readFully(buf, 0, n);
                    out.write(buf, 0, n);
                    len -= n;
                    written += n;
                }
            } else {
                throw new IOException("补丁里有未知操作 " + op);
            }
        }
        if (written != size) throw new IOException("补丁输出长度不对");
        return written;
    }

    private PortPatch() { }
}
