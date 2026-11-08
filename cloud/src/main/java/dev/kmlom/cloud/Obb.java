package dev.kmlom.cloud;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

// The expansion file Unity reads the player data from (main.1.dev.kmlom.mortal.obb): a plain zip with
// every entry stored, data aligned to 4 bytes (like zipalign), no data descriptors. The launcher writes
// it on the device from the Steam download and the APK's own port files.
final class Obb {
    static final int ALIGN = 4;

    static final class Item {
        String name;
        long header;  // local header offset
        long data;    // first data byte
        long size;
        int crc;
        String sha;   // hex SHA-1 of the data, when known
        boolean dir;
    }

    // ---- reading ----

    // The entries of an existing zip, from its central directory. Only stored entries are expected.
    static List<Item> read(File f) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            int tail = (int) Math.min(len, 65557);
            byte[] t = new byte[tail];
            r.seek(len - tail);
            r.readFully(t);
            int eocd = -1;
            for (int i = tail - 22; i >= 0; i--)
                if (t[i] == 0x50 && t[i + 1] == 0x4b && t[i + 2] == 5 && t[i + 3] == 6) { eocd = i; break; }
            if (eocd < 0) throw new IOException("不是完整的 zip");
            int count = u16(t, eocd + 10);
            long cdSize = u32(t, eocd + 12), cdOff = u32(t, eocd + 16);
            byte[] cd = new byte[(int) cdSize];
            r.seek(cdOff);
            r.readFully(cd);
            List<Item> out = new ArrayList<>(count);
            int p = 0;
            byte[] lh = new byte[30];
            for (int i = 0; i < count; i++) {
                if (u32(cd, p) != 0x02014b50L) throw new IOException("zip 目录损坏");
                Item it = new Item();
                int method = u16(cd, p + 10);
                it.crc = (int) u32(cd, p + 16);
                it.size = u32(cd, p + 24);
                int nl = u16(cd, p + 28), xl = u16(cd, p + 30), cl = u16(cd, p + 32);
                it.header = u32(cd, p + 42);
                it.name = new String(cd, p + 46, nl, StandardCharsets.UTF_8);
                it.dir = it.name.endsWith("/");
                p += 46 + nl + xl + cl;
                if (method != 0 && !it.dir) throw new IOException("OBB 里有压缩过的文件：" + it.name);
                r.seek(it.header);
                r.readFully(lh);
                it.data = it.header + 30 + u16(lh, 26) + u16(lh, 28);
                out.add(it);
            }
            return out;
        }
    }

    // SHA-1 of one stored entry's bytes.
    static String sha1(RandomAccessFile r, Item it) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] b = new byte[1 << 17];
        r.seek(it.data);
        for (long left = it.size; left > 0; ) {
            int n = (int) Math.min(b.length, left);
            r.readFully(b, 0, n);
            md.update(b, 0, n);
            left -= n;
        }
        return GameData.hex(md.digest());
    }

    // ---- writing ----

    interface Body {
        void write(OutputStream out) throws Exception;
    }

    static final class Writer implements Closeable {
        final RandomAccessFile raf;
        final List<Item> items = new ArrayList<>();
        long end;

        // Opens `f` for appending at `resumeAt` (0 = new file); `kept` are the entries before that point.
        Writer(File f, long resumeAt, List<Item> kept) throws IOException {
            raf = new RandomAccessFile(f, "rw");
            raf.setLength(resumeAt);
            raf.seek(resumeAt);
            end = resumeAt;
            if (kept != null) items.addAll(kept);
        }

        synchronized Item dir(String name) throws IOException {
            Item it = header(name.endsWith("/") ? name : name + "/");
            it.dir = true;
            items.add(it);
            return it;
        }

        // Appends one stored entry whose bytes `body` writes; returns it with its CRC and SHA-1.
        synchronized Item add(String name, Body body) throws Exception {
            Item it = header(name);
            CRC32 crc = new CRC32();
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            long[] n = new long[1];
            byte[] buf = new byte[1 << 20];
            int[] fill = new int[1];
            OutputStream o = new OutputStream() {
                @Override public void write(int b) throws IOException { write(new byte[] { (byte) b }, 0, 1); }

                @Override public void write(byte[] b, int off, int len) throws IOException {
                    crc.update(b, off, len);
                    md.update(b, off, len);
                    n[0] += len;
                    while (len > 0) {
                        int k = Math.min(len, buf.length - fill[0]);
                        System.arraycopy(b, off, buf, fill[0], k);
                        fill[0] += k; off += k; len -= k;
                        if (fill[0] == buf.length) { raf.write(buf, 0, fill[0]); fill[0] = 0; }
                    }
                }

                @Override public void flush() { }
            };
            try {
                body.write(o);
                if (fill[0] > 0) raf.write(buf, 0, fill[0]);
            } catch (Exception e) {
                // Drop the half-written entry.
                raf.setLength(it.header);
                raf.seek(it.header);
                end = it.header;
                throw e;
            }
            if (n[0] > 0xFFFFFFFFL) throw new IOException(name + " 超过 4 GB");
            it.size = n[0];
            it.crc = (int) crc.getValue();
            it.sha = GameData.hex(md.digest());
            end = it.data + it.size;
            byte[] fix = new byte[12];
            le32(fix, 0, it.crc);
            le32(fix, 4, it.size);
            le32(fix, 8, it.size);
            raf.seek(it.header + 14);
            raf.write(fix);
            raf.seek(end);
            items.add(it);
            return it;
        }

        private Item header(String name) throws IOException {
            byte[] nb = name.getBytes(StandardCharsets.UTF_8);
            long at = end;
            int pad = (int) ((ALIGN - (at + 30 + nb.length) % ALIGN) % ALIGN);
            byte[] h = new byte[30 + nb.length + pad];
            le32(h, 0, 0x04034b50);
            le16(h, 4, 10);
            le16(h, 10, 0);       // time
            le16(h, 12, 0x5C21);  // 2026-01-01
            le16(h, 26, nb.length);
            le16(h, 28, pad);
            System.arraycopy(nb, 0, h, 30, nb.length);
            raf.seek(at);
            raf.write(h);
            Item it = new Item();
            it.name = name;
            it.header = at;
            it.data = at + h.length;
            end = it.data;
            return it;
        }

        // Writes the central directory; the zip is complete after this.
        synchronized void finish() throws IOException {
            raf.seek(end);
            long cdStart = end;
            java.io.ByteArrayOutputStream cd = new java.io.ByteArrayOutputStream(items.size() * 96);
            for (Item it : items) {
                byte[] nb = it.name.getBytes(StandardCharsets.UTF_8);
                byte[] c = new byte[46];
                le32(c, 0, 0x02014b50);
                le16(c, 4, 20);
                le16(c, 6, 10);
                le16(c, 14, 0x5C21);
                le32(c, 16, it.crc);
                le32(c, 20, it.size);
                le32(c, 24, it.size);
                le16(c, 28, nb.length);
                le32(c, 38, it.dir ? 0x10 : 0);
                le32(c, 42, it.header);
                cd.write(c, 0, c.length);
                cd.write(nb, 0, nb.length);
            }
            byte[] cdb = cd.toByteArray();
            raf.write(cdb);
            byte[] e = new byte[22];
            le32(e, 0, 0x06054b50);
            le16(e, 8, items.size());
            le16(e, 10, items.size());
            le32(e, 12, cdb.length);
            le32(e, 16, cdStart);
            raf.write(e);
            raf.setLength(cdStart + cdb.length + e.length);
            raf.getFD().sync();
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }

    // Raw copy of an entry's data from another zip (an earlier OBB) into the writer.
    static Item copyFrom(Writer w, RandomAccessFile src, Item it) throws Exception {
        return w.add(it.name, out -> {
            byte[] b = new byte[1 << 17];
            long pos = it.data;
            for (long left = it.size; left > 0; ) {
                int n = (int) Math.min(b.length, left);
                synchronized (src) {
                    src.seek(pos);
                    src.readFully(b, 0, n);
                }
                out.write(b, 0, n);
                pos += n;
                left -= n;
            }
        });
    }

    private static int u16(byte[] b, int p) { return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8; }

    private static long u32(byte[] b, int p) { return (u16(b, p) | (long) u16(b, p + 2) << 16) & 0xFFFFFFFFL; }

    private static void le16(byte[] b, int p, int v) { b[p] = (byte) v; b[p + 1] = (byte) (v >>> 8); }

    private static void le32(byte[] b, int p, long v) { le16(b, p, (int) v); le16(b, p + 2, (int) (v >>> 16)); }

    private Obb() { }
}
