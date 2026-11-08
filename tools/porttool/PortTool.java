package dev.kmlom.cloud;

import in.dragonbra.javasteam.types.DepotManifest;
import in.dragonbra.javasteam.types.FileData;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;

// Builds the "port" the APK carries so the launcher can make the OBB on the device from the Steam
// download (see GameData). For every file of the PC-built OBB it picks how the device gets it:
//   copy    the Windows file from Steam, unchanged
//   stamp   a loose serialized file: only the target platform differs (BundleStamp.convertSerialized)
//   bstamp  an AssetBundle: only the target platform differs (BundleStamp.convert)
//   patch   Windows file + a delta in the APK (files with converted shaders), PortPatch
//   apk     shipped whole in the APK (code-side data, audio, catalog, bundles with converted shaders)
// and checks on the PC that the device-side code reproduces each file exactly.
//
// usage: PortTool <obbroot> <game dir> <depot manifest> <out dir> <patch cache dir> <build id>
// out dir gets port.txt, files/<obb path> (apk entries) and patch/<obb path>.lpt.
public class PortTool {
    static final String DATA = "assets/bin/Data/", AA = "assets/aa/StandaloneWindows64/";
    static final String ENGINE = "2020.3.49f1";

    public static void main(String[] a) throws Exception {
        Path obb = Paths.get(a[0]), game = Paths.get(a[1]), out = Paths.get(a[3]), cache = Paths.get(a[4]);
        DepotManifest man = DepotManifest.loadFromFile(a[2]);
        String build = a[5];
        Files.createDirectories(cache);
        deleteTree(out);
        Files.createDirectories(out);

        Map<String, FileData> depot = new TreeMap<>();
        for (FileData f : man.getFiles()) if (!f.getChunks().isEmpty()) depot.put(f.getFileName().replace('\\', '/'), f);

        List<Path> files = new ArrayList<>(), dirs = new ArrayList<>();
        try (var s = Files.walk(obb)) {
            s.sorted().forEach(p -> { if (Files.isDirectory(p)) { if (!p.equals(obb)) dirs.add(p); } else files.add(p); });
        }
        String guid = null;
        StringBuilder steam = new StringBuilder(), apk = new StringBuilder(), head = new StringBuilder();
        Set<String> consumed = new HashSet<>();
        long apkBytes = 0, patchBytes = 0, steamBytes = 0;
        Map<String, Integer> kinds = new TreeMap<>();
        for (Path p : files) {
            String rel = obb.relativize(p).toString().replace('\\', '/');
            String depotPath = null;
            if (rel.startsWith(DATA)) depotPath = "Mortal_Data/" + rel.substring(DATA.length());
            else if (rel.startsWith(AA)) depotPath = "Mortal_Data/StreamingAssets/aa/StandaloneWindows64/" + rel.substring(AA.length());
            if (rel.matches("assets/[0-9a-f]{32}")) guid = rel.substring(7);
            byte[] want = Files.readAllBytes(p);
            String wantSha = sha1(want);
            FileData df = depotPath == null ? null : depot.get(depotPath);
            String kind = "apk", outSha = wantSha, alt = "-", srcSha = "-";
            long outSize = want.length;
            if (df != null) {
                Path orig = game.resolve(depotPath);
                byte[] src = Files.readAllBytes(orig);
                srcSha = sha1(src);
                if (!srcSha.equals(hex(df.getFileHash()))) throw new IllegalStateException(depotPath + ": 本机游戏文件和清单 " + man.getManifestGID() + " 不一致");
                if (Arrays.equals(src, want)) {
                    kind = "copy";
                } else if (rel.endsWith(".bundle")) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream(src.length + 65536);
                    BundleStamp.Result r = BundleStamp.convert(new ByteArrayInputStream(src), bo);
                    byte[] dev = bo.toByteArray();
                    if (r.patched == r.serialized && Arrays.equals(StampCheck.unpack(dev), StampCheck.unpack(want))) {
                        kind = "bstamp";
                        outSha = sha1(dev);
                        outSize = dev.length;
                        alt = wantSha; // the PC-packed equivalent (adb-pushed OBBs carry it)
                    }
                } else if (want.length < (256 << 10)) {
                    // Small changed files (ScriptingAssemblies.json, boot.config, ...) change with every
                    // build: shipping them whole lets a new APK update the OBB without Steam.
                    kind = "apk";
                } else {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream(src.length);
                    int ok = BundleStamp.convertSerialized(new ByteArrayInputStream(src), bo, ENGINE);
                    if (ok == 1 && Arrays.equals(bo.toByteArray(), want)) {
                        kind = "stamp";
                    } else {
                        Path cached = cache.resolve(srcSha + "-" + wantSha + ".lpt");
                        byte[] patch;
                        if (Files.exists(cached)) patch = Files.readAllBytes(cached);
                        else {
                            long t0 = System.currentTimeMillis();
                            patch = gzip(diff(src, want));
                            System.out.printf("  diff %s: %d -> %d KB patch, %d ms%n", rel, want.length >> 10, patch.length >> 10, System.currentTimeMillis() - t0);
                        }
                        if (patch.length < want.length / 2) {
                            Path tmp = Files.createTempFile("lpt", ".src");
                            Files.write(tmp, src);
                            ByteArrayOutputStream po = new ByteArrayOutputStream(want.length);
                            try (RandomAccessFile rf = new RandomAccessFile(tmp.toFile(), "r")) {
                                PortPatch.apply(rf, new ByteArrayInputStream(patch), po);
                            }
                            Files.delete(tmp);
                            if (!Arrays.equals(po.toByteArray(), want)) throw new IllegalStateException(rel + ": 补丁还原结果不一致");
                            Files.write(cached, patch);
                            Path pp = out.resolve("patch/" + rel + ".lpt");
                            Files.createDirectories(pp.getParent());
                            Files.write(pp, patch);
                            patchBytes += patch.length;
                            kind = "patch";
                        }
                    }
                }
            }
            if (!kind.equals("apk")) {
                consumed.add(depotPath);
                steamBytes += df.getTotalSize();
            }
            kinds.merge(kind, 1, Integer::sum);
            String line = String.join("\t", "entry", kind, rel, outSha, Long.toString(outSize),
                    kind.equals("apk") ? "-" : depotPath, kind.equals("apk") ? "-" : srcSha, alt) + "\n";
            if (kind.equals("apk")) {
                Path fp = out.resolve("files/" + rel);
                Files.createDirectories(fp.getParent());
                Files.write(fp, want);
                apkBytes += want.length;
                apk.append(line);
            } else steam.append(line);
        }
        if (guid == null) throw new IllegalStateException("obbroot 里没有 build id 文件");
        head.append("app\t1859910\ndepot\t1859911\n");
        head.append("manifest\t").append(Long.toUnsignedString(man.getManifestGID())).append('\n');
        head.append("build\t").append(build).append('\n');
        head.append("engine\t").append(ENGINE).append('\n');
        head.append("guid\t").append(guid).append('\n');
        head.append("steambytes\t").append(steamBytes).append('\n');
        for (Path d : dirs) head.append("dir\t").append(obb.relativize(d).toString().replace('\\', '/')).append("/\n");
        // Every depot file the device does not fetch itself is an input of the APK: when Steam changes
        // one of them, the port needs a new APK.
        StringBuilder base = new StringBuilder();
        // Not inputs: the Windows executables and Mono runtime (the port runs IL2CPP), and the build stamp
        // that changes with every Steam build.
        for (Map.Entry<String, FileData> e : depot.entrySet())
            if (!consumed.contains(e.getKey()) && !e.getKey().endsWith(".exe") && !e.getKey().startsWith("MonoBleedingEdge/")
                    && !e.getKey().equals("Mortal_Data/StreamingAssets/build_info")) base.append("base\t").append(e.getKey()).append('\t').append(hex(e.getValue().getFileHash())).append('\n');
        Files.write(out.resolve("port.txt"), (head.toString() + steam + apk + base).getBytes(StandardCharsets.UTF_8));
        System.out.println("kinds " + kinds);
        System.out.printf("from Steam %.0f MB, APK files %.0f MB, patches %.1f MB%n", steamBytes / 1e6, apkBytes / 1e6, patchBytes / 1e6);
    }

    // ---- delta ----
    // Unity serialized files keep objects 8-byte aligned, and the converter only replaces some objects,
    // so matching 32-byte windows at 8-aligned offsets of both files finds everything that moved.
    static final int W = 32, STEP = 8;

    static byte[] diff(byte[] src, byte[] dst) throws IOException {
        int slots = Integer.highestOneBit(Math.max(16, src.length / STEP) * 2);
        int[] table = new int[slots];
        Arrays.fill(table, -1);
        ByteBuffer sb = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN), db = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN);
        int bits = Integer.numberOfTrailingZeros(slots);
        for (int i = 0; i + W <= src.length; i += STEP) {
            int h = hash(sb, i, bits);
            if (table[h] < 0) table[h] = i;
        }
        ByteArrayOutputStream ops = new ByteArrayOutputStream(1 << 20);
        DataOutputStream o = new DataOutputStream(ops);
        o.write(new byte[] { 'L', 'P', 'T', '1' });
        o.writeLong(dst.length);
        int lit = 0, p = 0;
        while (p + W <= dst.length) {
            int s = table[hash(db, p, bits)];
            if (s >= 0 && same(src, s, dst, p, W)) {
                int bs = s, bp = p;
                while (bp > lit && bs > 0 && src[bs - 1] == dst[bp - 1]) { bs--; bp--; }
                int e = p + W, es = s + W;
                while (e < dst.length && es < src.length && src[es] == dst[e]) { e++; es++; }
                if (bp > lit) add(o, dst, lit, bp - lit);
                o.write(PortPatch.COPY);
                o.writeLong(bs);
                o.writeInt(e - bp);
                lit = e;
                p = (e + STEP - 1) / STEP * STEP;
            } else p += STEP;
        }
        if (lit < dst.length) add(o, dst, lit, dst.length - lit);
        o.write(0);
        o.flush();
        return ops.toByteArray();
    }

    static void add(DataOutputStream o, byte[] d, int off, int len) throws IOException {
        o.write(PortPatch.ADD);
        o.writeInt(len);
        o.write(d, off, len);
    }

    static int hash(ByteBuffer b, int i, int bits) {
        long h = b.getLong(i) * 0x9E3779B97F4A7C15L;
        h = (h ^ b.getLong(i + 8)) * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ b.getLong(i + 16)) * 0x165667B19E3779F9L;
        h = (h ^ b.getLong(i + 24)) * 0x27D4EB2F165667C5L;
        return (int) (h >>> (64 - bits));
    }

    static boolean same(byte[] a, int ai, byte[] b, int bi, int n) {
        for (int k = 0; k < n; k++) if (a[ai + k] != b[bi + k]) return false;
        return true;
    }

    // ---- helpers ----
    static byte[] gzip(byte[] b) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(b.length / 2 + 64);
        try (GZIPOutputStream g = new GZIPOutputStream(bo, 1 << 16) { { def.setLevel(9); } }) { g.write(b); }
        return bo.toByteArray();
    }

    static String sha1(byte[] b) throws Exception { return hex(MessageDigest.getInstance("SHA-1").digest(b)); }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) { s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete()); }
    }
}
