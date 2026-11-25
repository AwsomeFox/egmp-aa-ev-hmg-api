package com.google.android.projection.protocol;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * NativeCheck and the relocatable recipe (recipes/ev-sensor.sites, mod.json native_sites):
 * the plain recipe HMG-API resolved for this library is read from its sites folder
 * (hmgapi.engine.SiteRecipes' names) and read back from memory like a fixed one.
 */
public final class SitesCheckTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    public static void main(String[] a) throws Exception {
        File t = Files.createTempDirectory("sites").toFile();
        File recipes = new File(t, "recipes");
        recipes.mkdirs();
        byte[] text = Files.readAllBytes(new File(a[0]).toPath());   // the shipped ev-sensor.sites
        Files.write(new File(recipes, "ev-sensor.sites").toPath(), text);
        NativeCheck.sSitesDir = new File(t, "sites");
        NativeCheck.load(recipes, "egmp-aa-ev-standalone");
        String lib = "0123456789abcdef0123456789abcdef";

        StringBuilder why = new StringBuilder();
        check("not resolved yet: nothing, and says so: " + why,
                NativeCheck.resolvedSites(lib, why).isEmpty() && why.toString().contains("not resolved for this library"));

        // what HMG-API keeps: <sites>/<mod>/<name, '/' made '_'>.<recipe md5, 8>.<library md5>.recipe
        File kept = new File(NativeCheck.sSitesDir, "egmp-aa-ev-standalone/recipes_ev-sensor.sites."
                + NativeCheck.md5(text).substring(0, 8) + "." + lib + ".recipe");
        kept.getParentFile().mkdirs();
        Files.write(kept.toPath(), ("# resolved for " + lib + " by hmgapi.engine.SiteFind\nid ev-sensor-sites\n"
                + "file /system/lib/libautoreceiver_infobank.so\nverify 0x145002 4 a b\nverify 0xbb3a0 8 c d\n"
                + "verify 0xbb73c 4 e f\npatch 0x145004 1628\npatch 0xbb3a2 4ff00007\npatch 0xbb73c f6f79bb9\n").getBytes("UTF-8"));
        why.setLength(0);
        List<NativeCheck.Recipe> rs = NativeCheck.resolvedSites(lib, why);
        check("resolved: its patch lines", rs.size() == 1 && rs.get(0).offsets.size() == 3
                && rs.get(0).offsets.get(0) == 0x145004L && rs.get(0).id.startsWith("ev-sensor-sites"));
        check("... sensor types up to 23 (no battery % curve)", NativeCheck.maxSensorType(rs.get(0)) == 23);

        final byte[] mem = new byte[0x150000];
        NativeCheck.Memory m = new NativeCheck.Memory() {
            public byte[] read(long off, int len) {
                return java.util.Arrays.copyOfRange(mem, (int) off, (int) off + len);
            }
        };
        why.setLength(0);
        check("not in memory: not found", NativeCheck.find(rs, m, why) == null);
        for (int i = 0; i < rs.get(0).offsets.size(); i++) {
            byte[] b = hex(rs.get(0).bytes.get(i));
            System.arraycopy(b, 0, mem, (int) (long) rs.get(0).offsets.get(i), b.length);
        }
        check("in memory: found", rs.get(0).id.equals(NativeCheck.find(rs, m, new StringBuilder())));

        File no = new File(kept.getPath().replace(".recipe", ".why"));
        kept.delete();
        Files.write(no.toPath(), "site qo: no match\n".getBytes("UTF-8"));
        why.setLength(0);
        check("doesn't fit this build: HMG-API's reason", NativeCheck.resolvedSites(lib, why).isEmpty()
                && why.toString().equals("recipes/ev-sensor.sites: site qo: no match"));

        check("E-GMP by model code", HmgApiEntry.egmp("CVEV.CAN.0000.V015.010.260206") && HmgApiEntry.egmp("NEEV23SE.USA.0000.V015.012.260507")
                && !HmgApiEntry.egmp("OSEV.USA.0000.V015.012.260507") && !HmgApiEntry.egmp("SG2EV.USA.0000.V015.012.260507")
                && HmgApiEntry.egmp(""));
        check("road load fitted: Ioniq 5 and EV6, not the Ioniq 6 or other EVs",
                HmgApiEntry.roadFitted("NEEV23SE.USA.0000.V015.012.260507") && HmgApiEntry.roadFitted("CVEV.CAN.0000.V015.010.260206")
                && !HmgApiEntry.roadFitted("CEEV.USA.0000.V015.012.260903") && !HmgApiEntry.roadFitted("OSEV.USA.0000.V015.012.260507")
                && HmgApiEntry.roadFitted(""));
        System.out.println("SitesCheckTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
