package dev.kmlom.cloud;

import java.io.*;
import java.nio.file.*;
import java.util.*;

// PC check: does BundleStamp(original Windows bundle) unpack to exactly what the PC converter
// (lomtool rt-bundles + rt-pack) produced? Lists bundles that differ beyond the platform field.
// usage: LomStampCheck <original dir> <converted dir>
public class LomStampCheck {
    public static void main(String[] a) throws Exception {
        File[] files = new File(a[0]).listFiles((d, n) -> n.endsWith(".bundle"));
        Arrays.sort(files);
        int ok = 0, bad = 0;
        long t0 = System.currentTimeMillis();
        for (File f : files) {
            byte[] src = Files.readAllBytes(f.toPath());
            ByteArrayOutputStream bo = new ByteArrayOutputStream(src.length + 4096);
            BundleStamp.Result r = BundleStamp.convert(new ByteArrayInputStream(src), bo);
            byte[] u1 = StampCheck.unpack(bo.toByteArray());
            File c = new File(a[1], f.getName());
            if (!c.exists()) { System.out.println("MISSING " + f.getName()); bad++; continue; }
            byte[] u2 = StampCheck.unpack(Files.readAllBytes(c.toPath()));
            int diff = 0;
            if (u1.length != u2.length) diff = -1;
            else for (int i = 0; i < u1.length; i++) if (u1[i] != u2[i]) diff++;
            if (diff != 0 || r.patched != r.serialized) {
                bad++;
                System.out.println("DIFF " + f.getName() + " len " + u1.length + "/" + u2.length + " bytes=" + diff + " ser=" + r.serialized + " patched=" + r.patched + " size=" + src.length);
            } else ok++;
        }
        System.out.printf("same %d, different %d, %d s%n", ok, bad, (System.currentTimeMillis() - t0) / 1000);
    }
}
