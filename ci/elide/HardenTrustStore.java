import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;

/** Oracle's October 2026 CSPU workaround for CVE-2026-91403; not a replacement for the CPU.
 * https://www.graalvm.org/uploads/native-image-advisory-oct-2026-cspu.pdf
 */
class HardenTrustStore {
    private static final Map<String, String> DISTRUSTED = Map.of(
        "emsigneccrootcag3 [jdk]", "86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b",
        "comodoaaaca [jdk]", "d7a7a0fb5d7e2731d771e9484ebcdef71d5f0c3e0a2948782bc83ee0ea699ef4");

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: HardenTrustStore.java <cacerts>");
        }
        Path path = Path.of(args[0]).toRealPath();
        char[] password = "changeit".toCharArray();
        // Detect JKS or PKCS12, then explicitly authenticate rather than trusting format probing.
        KeyStore store = KeyStore.getInstance(path.toFile(), password);
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        var aliases = store.aliases();
        var removed = new ArrayList<String>();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            var certificate = store.getCertificate(alias);
            String fingerprint = certificate == null ? "" :
                HexFormat.of().formatHex(digest.digest(certificate.getEncoded()));
            String expected = DISTRUSTED.get(alias);
            if (expected != null && !expected.equals(fingerprint)) {
                throw new IllegalStateException("Unexpected certificate at distrusted alias: " + alias);
            }
            if (DISTRUSTED.containsValue(fingerprint)) {
                removed.add(alias);
            }
        }
        for (String alias : removed) {
            store.deleteEntry(alias);
        }
        if (!removed.isEmpty()) {
            Path staged = Files.createTempFile(path.getParent(), ".cacerts-", ".tmp");
            try {
                Files.copy(path, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                try (OutputStream output = Files.newOutputStream(staged)) {
                    store.store(output, password);
                }
                // Do not expose a partially written store, or continue if replacement fails.
                Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(staged);
            }
        }
        System.out.println("CVE-2026-91403: removed " + removed.size() + " distrusted roots: " + removed);
    }
}
