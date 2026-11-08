package dev.kmlom.cloud;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.StatFs;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import in.dragonbra.javasteam.types.DepotManifest;
import in.dragonbra.javasteam.types.FileData;

// The game's data: Unity reads it from the expansion file (OBB). The launcher makes that file on the
// device from the Windows depot on Steam plus what the APK carries (assets/port, built on the PC by
// tools/porttool/PortTool):
//   copy / stamp / bstamp   Windows file from Steam, as is or with the target platform field rewritten
//   patch                   Windows file from Steam + a small delta (converted shaders)
//   apk                     shipped in the APK (code-side data, audio, catalog, a few bundles)
//
// The APK is built from one depot manifest (port.txt "manifest"); a first install fetches exactly that
// version. A newer Steam version can be installed on the device only if nothing the APK was built from
// changed (port.txt "base", patch sources); otherwise the port needs a new APK. A new APK reuses all the
// data that did not change, so only the difference is downloaded.
//
// files/port/obb.state lists the OBB's entries (offset, SHA-1, the Steam file each came from); an
// interrupted build resumes from files/port/partial.state.
public final class GameData {
    private static final String TAG = "LomData";
    static final String OBB_NAME = "main.1.dev.kmlom.mortal.obb";

    // ---- what the APK carries ----
    static final class Entry {
        String kind, path, outSha, depotPath, srcSha, alt;
        long size;

        boolean steam() {
            return !kind.equals("apk");
        }
    }

    public static final class Port {
        public int app, depot;
        public long manifest, build, steamBytes;
        public String engine, guid;
        final List<String> dirs = new ArrayList<>();
        final List<Entry> entries = new ArrayList<>();
        final Map<String, String> base = new HashMap<>();
    }

    private static Port port;

    public static synchronized Port port(Context c) {
        if (port != null) return port;
        Port p = new Port();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("port/port.txt"), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split("\t");
                switch (f[0]) {
                    case "app": p.app = Integer.parseInt(f[1]); break;
                    case "depot": p.depot = Integer.parseInt(f[1]); break;
                    case "manifest": p.manifest = Long.parseUnsignedLong(f[1]); break;
                    case "build": p.build = Long.parseLong(f[1]); break;
                    case "engine": p.engine = f[1]; break;
                    case "guid": p.guid = f[1]; break;
                    case "steambytes": p.steamBytes = Long.parseLong(f[1]); break;
                    case "dir": p.dirs.add(f[1]); break;
                    case "base": p.base.put(f[1], f[2]); break;
                    case "entry": {
                        Entry e = new Entry();
                        e.kind = f[1];
                        e.path = f[2];
                        e.outSha = f[3];
                        e.size = Long.parseLong(f[4]);
                        e.depotPath = f[5];
                        e.srcSha = f[6];
                        e.alt = f[7];
                        p.entries.add(e);
                        break;
                    }
                    default: break;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("APK 里缺少 port/port.txt", e);
        }
        port = p;
        return p;
    }

    // ---- the OBB and its record ----
    public static File obbFile(Context c) {
        return new File(c.getObbDir(), OBB_NAME);
    }

    private static File partialFile(Context c) {
        return new File(c.getObbDir(), OBB_NAME + ".partial");
    }

    private static File stateDir(Context c) {
        File d = new File(c.getFilesDir(), "port");
        d.mkdirs();
        return d;
    }

    static final class Rec {
        Obb.Item item;
        String src; // SHA-1 of the Steam file it was made from ("-" for APK files)
    }

    static final class Record {
        long manifest;
        String guid = "";
        boolean complete;
        long apkStart = -1;
        final Map<String, Rec> items = new LinkedHashMap<>();

        long end() {
            long e = 0;
            for (Rec r : items.values()) e = Math.max(e, r.item.data + r.item.size);
            return e;
        }
    }

    private static Record readRecord(File f) {
        if (!f.exists()) return null;
        Record s = new Record();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] x = line.split("\t");
                switch (x[0]) {
                    case "manifest": s.manifest = Long.parseUnsignedLong(x[1]); break;
                    case "guid": s.guid = x[1]; break;
                    case "apkstart": s.apkStart = Long.parseLong(x[1]); break;
                    case "complete": s.complete = true; break;
                    case "i": {
                        if (x.length < 8) break; // a line cut short by a crash
                        Rec rec = new Rec();
                        Obb.Item it = new Obb.Item();
                        it.header = Long.parseLong(x[1]);
                        it.data = Long.parseLong(x[2]);
                        it.size = Long.parseLong(x[3]);
                        it.crc = (int) Long.parseLong(x[4]);
                        it.sha = x[5];
                        rec.src = x[6];
                        it.name = x[7];
                        it.dir = it.name.endsWith("/");
                        rec.item = it;
                        s.items.put(it.name, rec);
                        break;
                    }
                    default: break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "record unreadable: " + e);
            return null;
        }
        return s;
    }

    private static void writeRecord(File f, Record s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write("manifest\t" + Long.toUnsignedString(s.manifest) + "\n");
            w.write("guid\t" + s.guid + "\n");
            if (s.apkStart >= 0) w.write("apkstart\t" + s.apkStart + "\n");
            for (Rec r : s.items.values()) w.write(line(r));
            if (s.complete) w.write("complete\n");
        }
        if (!tmp.renameTo(f)) throw new IOException("无法写入记录 " + f.getName());
    }

    private static String line(Rec r) {
        Obb.Item it = r.item;
        return "i\t" + it.header + "\t" + it.data + "\t" + it.size + "\t" + (it.crc & 0xFFFFFFFFL) + "\t" + it.sha + "\t" + r.src + "\t" + it.name + "\n";
    }

    private static synchronized void appendRecord(File f, Rec r) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            w.write(line(r));
        }
    }

    private static File liveState(Context c) {
        return new File(stateDir(c), "obb.state");
    }

    private static File partialState(Context c) {
        return new File(stateDir(c), "partial.state");
    }

    // ---- status ----
    public enum Status { READY, MISSING, APK_CHANGED }

    public static Status status(Context c) {
        Port p = port(c);
        File f = obbFile(c);
        if (!f.exists()) return Status.MISSING;
        Record s = readRecord(liveState(c));
        if (s != null && s.complete && s.items.size() > 0) return s.guid.equals(p.guid) ? Status.READY : Status.APK_CHANGED;
        if (s != null && !s.complete) return Status.APK_CHANGED; // an in-place update was cut short
        // No record: an OBB pushed over adb by the developer. Unity accepts it when it carries this
        // APK's build id.
        try {
            for (Obb.Item it : Obb.read(f)) if (it.name.equals("assets/" + p.guid)) return Status.READY;
            return Status.APK_CHANGED;
        } catch (Exception e) {
            Log.w(TAG, "obb unreadable: " + e);
            return Status.MISSING;
        }
    }

    public static boolean ready(Context c) {
        return status(c) == Status.READY;
    }

    // The Steam version the installed data is from (0 = none).
    public static long installedManifest(Context c) {
        if (!obbFile(c).exists()) return 0;
        Record s = readRecord(liveState(c));
        return s != null && s.complete ? s.manifest : port(c).manifest;
    }

    public static long freeBytes(Context c) {
        File d = c.getObbDir();
        d.mkdirs();
        return new StatFs(d.getPath()).getAvailableBytes();
    }

    // Bytes the partial build has already written (for "continue download").
    public static long partialBytes(Context c) {
        File f = partialFile(c);
        return f.exists() ? f.length() : 0;
    }

    // ---- sources ----
    static final class Remote {
        final String sha;
        final long size;
        final Object ref;

        Remote(String sha, long size, Object ref) {
            this.sha = sha;
            this.size = size;
            this.ref = ref;
        }
    }

    public interface Listener {
        void status(String s);

        void progress(long done, long total, int files, int filesTotal);
    }

    interface Source extends AutoCloseable {
        Map<String, Remote> files(long manifest) throws Exception;

        void fetch(Remote r, File dst, java.util.function.LongConsumer bytes, AtomicBoolean cancel) throws Exception;

        @Override
        void close();
    }

    static final class SteamSource implements Source {
        final SteamSession session;
        final SteamDepot depot;
        private final Map<Long, Map<String, Remote>> manifests = new HashMap<>();

        SteamSource(Context c, Port port) throws Exception {
            TokenStore t = TokenStore.load(c);
            if (t == null) throw new IOException("需要先登录 Steam");
            session = new SteamSession();
            try {
                session.logOn(t.account, t.refreshToken, TokenStore.loginId(c) ^ 0x20000);
            } catch (Exception e) {
                session.close();
                throw e;
            }
            depot = new SteamDepot(session, port.app, port.depot);
        }

        long publicManifest() throws Exception {
            return depot.branchManifest("public");
        }

        @Override
        public synchronized Map<String, Remote> files(long gid) throws Exception {
            Map<String, Remote> m = manifests.get(gid);
            if (m != null) return m;
            DepotManifest dm = depot.manifest(gid);
            m = new HashMap<>();
            for (FileData f : dm.getFiles()) {
                if (f.getChunks().isEmpty()) continue;
                m.put(f.getFileName().replace('\\', '/'), new Remote(hex(f.getFileHash()), f.getTotalSize(), f));
            }
            manifests.put(gid, m);
            return m;
        }

        @Override
        public void fetch(Remote r, File dst, java.util.function.LongConsumer bytes, AtomicBoolean cancel) throws Exception {
            try (RandomAccessFile f = new RandomAccessFile(dst, "rw")) {
                depot.download((FileData) r.ref, f, bytes::accept, cancel);
            }
        }

        @Override
        public void close() {
            depot.close();
            session.close();
        }
    }

    // Developer test: the Windows game folder (Mortal_Data and the rest, as in the depot) copied to a
    // folder on the device stands in for Steam.
    static final class LocalSource implements Source {
        final File dir;
        final Port port;

        LocalSource(File dir, Port port) {
            this.dir = dir;
            this.port = port;
        }

        @Override
        public Map<String, Remote> files(long manifest) {
            Map<String, Remote> m = new HashMap<>();
            for (Entry e : port.entries) {
                if (!e.steam()) continue;
                File f = new File(dir, e.depotPath);
                if (f.exists()) m.put(e.depotPath, new Remote(e.srcSha, f.length(), f));
            }
            for (Map.Entry<String, String> b : port.base.entrySet()) m.put(b.getKey(), new Remote(b.getValue(), 0, null));
            return m;
        }

        @Override
        public void fetch(Remote r, File dst, java.util.function.LongConsumer bytes, AtomicBoolean cancel) throws Exception {
            try (InputStream in = new FileInputStream((File) r.ref); OutputStream out = new FileOutputStream(dst)) {
                byte[] b = new byte[1 << 16];
                for (int n; (n = in.read(b)) > 0; ) {
                    if (cancel.get()) throw new InterruptedException();
                    out.write(b, 0, n);
                    bytes.accept(n);
                }
            }
        }

        @Override
        public void close() { }
    }

    // The files of the APK's own manifest, from port.txt alone (no Steam connection needed to plan).
    private static Map<String, Remote> baseFiles(Port p) {
        Map<String, Remote> m = new HashMap<>();
        for (Entry e : p.entries) if (e.steam()) m.put(e.depotPath, new Remote(e.srcSha, -1, null));
        for (Map.Entry<String, String> b : p.base.entrySet()) m.put(b.getKey(), new Remote(b.getValue(), -1, null));
        return m;
    }

    // ---- planning ----
    static final class NeedsNewApk extends Exception {
        NeedsNewApk(String why) {
            super(why);
        }
    }

    static final class Job {
        Entry e;
        Remote remote;   // the Steam file (download jobs)
        Rec reuse;       // or: an entry of the old OBB with the right bytes
        boolean verify;  // the output's SHA-1 is known (port.txt)
    }

    static final class Plan {
        long target;
        final List<Job> jobs = new ArrayList<>();
        long downloadBytes, writeBytes;
        int downloads;
        boolean inPlace; // only the APK part changed: rewrite the OBB's tail where it is
    }

    private static boolean ignored(String depotPath) {
        return depotPath.endsWith(".exe") || depotPath.startsWith("MonoBleedingEdge/") || depotPath.equals("Mortal_Data/StreamingAssets/build_info");
    }

    // Decides, for every entry of the OBB to make, where its bytes come from.
    private static Plan plan(Port p, long target, Map<String, Remote> files, Map<String, Rec> old) throws NeedsNewApk {
        if (target != p.manifest) {
            for (Map.Entry<String, String> b : p.base.entrySet()) {
                Remote r = files.get(b.getKey());
                if (r == null || !r.sha.equals(b.getValue())) throw new NeedsNewApk(b.getKey());
            }
            Set<String> known = new HashSet<>(p.base.keySet());
            for (Entry e : p.entries) if (e.steam()) known.add(e.depotPath);
            for (String n : files.keySet()) if (!known.contains(n) && !ignored(n)) throw new NeedsNewApk("新文件 " + n);
        }
        Plan plan = new Plan();
        plan.target = target;
        for (Entry e : p.entries) {
            Job j = new Job();
            j.e = e;
            Rec o = old.get(e.path);
            if (!e.steam()) {
                j.verify = true;
                if (o != null && e.outSha.equals(o.item.sha)) j.reuse = o;
            } else {
                Remote r = files.get(e.depotPath);
                if (r == null) throw new NeedsNewApk("缺少 " + e.depotPath);
                boolean same = r.sha.equals(e.srcSha);
                if (e.kind.equals("patch") && !same) throw new NeedsNewApk(e.depotPath);
                j.verify = same;
                if (o != null && o.item.sha != null
                        && (same ? (o.item.sha.equals(e.outSha) || o.item.sha.equals(e.alt)) : r.sha.equals(o.src))) j.reuse = o;
                else {
                    j.remote = r;
                    plan.downloads++;
                    plan.downloadBytes += Math.max(0, r.size);
                }
            }
            plan.writeBytes += e.size;
            plan.jobs.add(j);
        }
        return plan;
    }

    // The entries of the current OBB, for reuse. An OBB without a record (pushed by the developer) is
    // read from its zip directory and hashed, which takes a little while for 2.4 GB.
    private static Map<String, Rec> oldEntries(Context c, Port p, Listener l, AtomicBoolean cancel) throws Exception {
        File f = obbFile(c);
        Map<String, Rec> out = new HashMap<>();
        if (!f.exists()) return out;
        Record s = readRecord(liveState(c));
        // A record we wrote, complete or cut short during an in-place update (every entry it lists
        // was written in full before it was recorded).
        if (s != null && !s.items.isEmpty()) return s.items;
        Map<String, Entry> wanted = new HashMap<>();
        for (Entry e : p.entries) wanted.put(e.path, e);
        List<Obb.Item> items;
        try {
            items = Obb.read(f);
        } catch (Exception e) {
            Log.w(TAG, "old obb unusable: " + e);
            return out;
        }
        long total = 0, done = 0;
        for (Obb.Item it : items) if (wanted.containsKey(it.name)) total += it.size;
        l.status("正在核对已有的游戏数据…");
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            int n = 0;
            for (Obb.Item it : items) {
                Entry e = wanted.get(it.name);
                if (e == null) continue;
                if (cancel.get()) throw new InterruptedException("已暂停");
                it.sha = Obb.sha1(r, it);
                Rec rec = new Rec();
                rec.item = it;
                rec.src = e.steam() && (it.sha.equals(e.outSha) || it.sha.equals(e.alt)) ? e.srcSha : "-";
                out.put(it.name, rec);
                done += it.size;
                l.progress(done, total, ++n, wanted.size());
            }
        }
        return out;
    }

    // ---- update check (needs Steam) ----
    public static final class Check {
        public long installed, apkBase, latest, target;
        public boolean upToDate, needsNewApk;
        public String whyNewApk;
        public int filesToGet;
        public long bytesToGet;
    }

    public static Check check(Context c, Listener l) throws Exception {
        Port p = port(c);
        Check r = new Check();
        r.installed = installedManifest(c);
        r.apkBase = p.manifest;
        try (SteamSource src = new SteamSource(c, p)) {
            l.status("正在向 Steam 查询最新版本…");
            r.latest = src.publicManifest();
            long target = p.manifest;
            if (r.latest != p.manifest) {
                try {
                    plan(p, r.latest, src.files(r.latest), new HashMap<>());
                    target = r.latest;
                } catch (NeedsNewApk e) {
                    r.needsNewApk = true;
                    r.whyNewApk = e.getMessage();
                }
            }
            r.target = target;
            if (status(c) == Status.READY && r.installed == target) {
                r.upToDate = true;
                return r;
            }
            Record live = readRecord(liveState(c));
            if (live == null && obbFile(c).exists() && target == p.manifest) {
                // An OBB pushed by the developer: what it can supply is only known after hashing it
                // (install does that), so the size is left open.
                r.bytesToGet = -1;
                return r;
            }
            Map<String, Rec> old = live != null ? live.items : new HashMap<>();
            Plan plan = plan(p, target, target == p.manifest ? baseFiles(p) : src.files(target), old);
            if (plan.downloads > 0 && target == p.manifest) plan = plan(p, target, src.files(target), old);
            r.filesToGet = plan.downloads;
            r.bytesToGet = plan.downloadBytes;
        }
        return r;
    }

    // ---- install ----
    // Makes (or brings up to date) the OBB for `target` (0 = the APK's own manifest). Downloads from
    // Steam only what neither the old OBB nor the APK has; `localTest` replaces Steam for testing.
    public static void install(Context c, long target, File localTest, Listener l, AtomicBoolean cancel) throws Exception {
        Port p = port(c);
        if (target == 0) target = p.manifest;
        Source src = null;
        try {
            Map<String, Rec> old = oldEntries(c, p, l, cancel);
            // The APK's own version can be planned from port.txt alone, so an update that only needs
            // data already on the device works without Steam.
            boolean fromPort = target == p.manifest;
            if (!fromPort) {
                l.status("正在连接 Steam…");
                src = localTest != null ? new LocalSource(localTest, p) : new SteamSource(c, p);
            }
            Plan plan = plan(p, target, fromPort ? baseFiles(p) : src.files(target), old);
            if (plan.downloads > 0 && fromPort) {
                l.status("正在连接 Steam…");
                src = localTest != null ? new LocalSource(localTest, p) : new SteamSource(c, p);
                plan = plan(p, target, src.files(target), old);
            }
            run(c, p, plan, src, l, cancel);
        } finally {
            if (src != null) src.close();
        }
    }

    private static void run(Context c, Port p, Plan plan, Source src, Listener l, AtomicBoolean cancel) throws Exception {
        File live = obbFile(c), part = partialFile(c), dl = new File(c.getFilesDir(), "port-dl");
        c.getObbDir().mkdirs();
        dl.mkdirs();
        Record liveRec = readRecord(liveState(c));

        // Only the APK's files changed and the OBB is one we wrote: rewrite its tail in place.
        boolean inPlace = live.exists() && liveRec != null && liveRec.apkStart > 0 && liveRec.manifest == plan.target;
        if (inPlace) for (Job j : plan.jobs) if (j.e.steam() && (j.reuse == null || j.reuse.item.header >= liveRec.apkStart)) { inPlace = false; break; }

        File target = inPlace ? live : part;
        File stateF = inPlace ? liveState(c) : partialState(c);
        final Record rec = startRecord(p, plan, inPlace, liveRec, stateF, part);
        writeRecord(stateF, rec);

        // What is still to write, and the space for it.
        List<Job> todo = new ArrayList<>();
        long remaining = 0, downloadTotal = 0;
        for (Job j : plan.jobs) {
            Rec have = rec.items.get(j.e.path);
            if (have != null) continue;
            if (inPlace && j.reuse != null && j.e.steam()) continue;
            todo.add(j);
            remaining += j.e.size;
            if (j.remote != null) downloadTotal += Math.max(j.remote.size, 0);
        }
        long need = remaining + (512L << 20);
        long free = freeBytes(c);
        if (free < need) throw new IOException(String.format("存储空间不够：还需要约 %.1f GB，现在只剩 %.1f GB", need / 1e9, free / 1e9));

        AssetManager assets = c.getAssets();
        AtomicLong done = new AtomicLong();
        AtomicInteger filesDone = new AtomicInteger();
        int filesTotal = todo.size();
        long total = remaining;
        try (Obb.Writer w = new Obb.Writer(target, inPlace ? liveRec.apkStart : rec.end(), new ArrayList<Obb.Item>() {{
            for (Rec r : rec.items.values()) add(r.item);
        }})) {
            // 1. Folders, and what the old OBB already has.
            for (String d : p.dirs) if (!rec.items.containsKey(d)) record(stateF, rec, w.dir(d), "-");
            List<Job> downloads = new ArrayList<>(), apk = new ArrayList<>();
            boolean anyReuse = false;
            for (Job j : todo) {
                if (j.remote != null) downloads.add(j);
                else if (!j.e.steam()) apk.add(j);
                else anyReuse = true;
            }
            if (anyReuse) {
                l.status("正在整理已有的游戏数据…");
                try (RandomAccessFile old = new RandomAccessFile(live, "r")) {
                    for (Job j : todo) {
                        if (j.remote != null || !j.e.steam()) continue;
                        if (cancel.get()) throw new InterruptedException("已暂停，下次会从这里继续");
                        Obb.Item it = Obb.copyFrom(w, old, j.reuse.item);
                        record(stateF, rec, it, j.reuse.src);
                        done.addAndGet(j.e.size);
                        l.progress(done.get(), total, filesDone.incrementAndGet(), filesTotal);
                    }
                }
            }

            // 2. Steam downloads, eight at a time; each file goes into the OBB as soon as it is here.
            if (!downloads.isEmpty()) {
                l.status("正在从 Steam 下载游戏数据");
                AtomicBoolean failed = new AtomicBoolean();
                Exception[] first = new Exception[1];
                ExecutorService pool = Executors.newFixedThreadPool(6, r -> {
                    Thread t = new Thread(r, "LomData-dl");
                    t.setDaemon(true);
                    return t;
                });
                AtomicInteger seq = new AtomicInteger();
                for (Job j : downloads) {
                    pool.submit(() -> {
                        if (cancel.get() || failed.get()) return;
                        File raw = new File(dl, seq.incrementAndGet() + ".raw");
                        AtomicLong mine = new AtomicLong();
                        try {
                            src.fetch(j.remote, raw, d -> {
                                mine.addAndGet(d);
                                // Downloads count by the bytes they will put in the OBB.
                                done.addAndGet(j.remote.size > 0 ? d * j.e.size / j.remote.size : 0);
                            }, cancel);
                            Obb.Item it = w.add(j.e.path, out -> transform(assets, p, j.e, raw, out));
                            if (j.verify && !it.sha.equals(j.e.outSha)) throw new IOException(j.e.path + " 转换后校验失败");
                            record(stateF, rec, it, j.remote.sha);
                            long counted = j.remote.size > 0 ? mine.get() * j.e.size / j.remote.size : 0;
                            done.addAndGet(j.e.size - counted);
                            l.progress(done.get(), total, filesDone.incrementAndGet(), filesTotal);
                        } catch (Exception ex) {
                            done.addAndGet(-(j.remote.size > 0 ? mine.get() * j.e.size / j.remote.size : 0));
                            if (cancel.get()) return;
                            if (failed.compareAndSet(false, true)) first[0] = ex;
                            Log.w(TAG, "failed " + j.e.path + ": " + ex);
                        } finally {
                            raw.delete();
                        }
                    });
                }
                pool.shutdown();
                while (!pool.awaitTermination(500, TimeUnit.MILLISECONDS)) l.progress(done.get(), total, filesDone.get(), filesTotal);
                if (cancel.get()) throw new InterruptedException("已暂停，下次会从这里继续");
                if (failed.get())
                    throw new IOException("下载没有完成：" + SteamDepot.rootMessage(first[0]) + "\n已下载的部分会保留，重试会接着下载。", first[0]);
            }

            // 3. The APK's files, last, so a later APK can replace just this tail.
            l.status("正在安装游戏数据…");
            rec.apkStart = w.end;
            writeRecord(stateF, rec);
            for (Job j : apk) {
                if (cancel.get()) throw new InterruptedException("已暂停，下次会从这里继续");
                Obb.Item it = w.add(j.e.path, out -> {
                    try (InputStream in = assets.open("port/files/" + j.e.path)) {
                        copy(in, out);
                    }
                });
                if (!it.sha.equals(j.e.outSha)) throw new IOException(j.e.path + " 校验失败（APK 不完整？）");
                record(stateF, rec, it, "-");
                done.addAndGet(j.e.size);
                l.progress(done.get(), total, filesDone.incrementAndGet(), filesTotal);
            }
            w.finish();
        }
        rec.complete = true;
        if (!inPlace) {
            if (live.exists() && !live.delete()) throw new IOException("无法替换旧的游戏数据");
            if (!part.renameTo(live)) throw new IOException("无法写入游戏数据");
        }
        writeRecord(liveState(c), rec);
        partialState(c).delete();
        deleteTree(dl);
        Log.i(TAG, "obb ready: manifest " + Long.toUnsignedString(rec.manifest) + ", " + rec.items.size() + " entries, " + live.length() + " bytes");
        l.progress(total, total, filesTotal, filesTotal);
    }

    // The record the build appends to: in place, the live one cut at the APK part; otherwise the
    // partial one if it is for the same target, else a fresh one.
    private static Record startRecord(Port p, Plan plan, boolean inPlace, Record liveRec, File stateF, File part) {
        Record rec;
        if (inPlace) {
            rec = new Record();
            rec.manifest = plan.target;
            rec.guid = p.guid;
            rec.apkStart = liveRec.apkStart; // so a cut-short rewrite resumes in place too
            // Keep only what the new port still takes from Steam (and the folders); anything else
            // before the APK part (a file this APK now carries itself) stays as unreferenced bytes.
            java.util.Set<String> keep = new java.util.HashSet<>(p.dirs);
            for (Entry e : p.entries) if (e.steam()) keep.add(e.path);
            for (Rec r : liveRec.items.values())
                if (r.item.header < liveRec.apkStart && keep.contains(r.item.name)) rec.items.put(r.item.name, r);
            return rec;
        }
        rec = readRecord(stateF);
        if (rec == null || rec.complete || rec.manifest != plan.target || !rec.guid.equals(p.guid) || !part.exists()) {
            part.delete();
            rec = new Record();
            rec.manifest = plan.target;
            rec.guid = p.guid;
        }
        return rec;
    }

    private static void record(File stateF, Record rec, Obb.Item it, String src) throws IOException {
        Rec r = new Rec();
        r.item = it;
        r.src = src;
        synchronized (rec) {
            rec.items.put(it.name, r);
        }
        appendRecord(stateF, r);
    }

    private static void transform(AssetManager assets, Port p, Entry e, File raw, OutputStream out) throws Exception {
        switch (e.kind) {
            case "copy":
                try (InputStream in = new FileInputStream(raw)) { copy(in, out); }
                break;
            case "stamp":
                try (InputStream in = new BufferedInputStream(new FileInputStream(raw), 1 << 16)) {
                    if (BundleStamp.convertSerialized(in, out, p.engine) != 1) throw new IOException(e.path + "：认不出文件头");
                }
                break;
            case "bstamp":
                try (InputStream in = new BufferedInputStream(new FileInputStream(raw), 1 << 16)) {
                    BundleStamp.Result r = BundleStamp.convert(in, out);
                    if (r.patched != r.serialized) throw new IOException(e.path + "：" + r.patched + "/" + r.serialized + " 个序列化文件改写成功");
                }
                break;
            case "patch":
                try (RandomAccessFile s = new RandomAccessFile(raw, "r"); InputStream pt = assets.open("port/patch/" + e.path + ".lpt")) {
                    PortPatch.apply(s, pt, out);
                }
                break;
            default:
                throw new IOException("未知类型 " + e.kind);
        }
    }

    // ---- helpers ----
    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[1 << 17];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
    }

    static String hex(byte[] b) {
        if (b == null) return null;
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    static String sha1(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[1 << 16];
            for (int r; (r = in.read(b)) > 0; ) md.update(b, 0, r);
        }
        return hex(md.digest());
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private GameData() { }
}
