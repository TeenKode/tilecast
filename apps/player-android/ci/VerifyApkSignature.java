import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;

/** Verify the APK and its pinned signer without parsing apksigner's display format. */
public final class VerifyApkSignature {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Usage: VerifyApkSignature <apk> <expected-sha256>");
        }
        ApkVerifier.Result result = new ApkVerifier.Builder(new File(args[0])).build().verify();
        if (!result.isVerified()) {
            throw new SecurityException("APK verification failed: " + result.getErrors());
        }
        List<X509Certificate> signers = result.getSignerCertificates();
        if (signers.size() != 1) {
            throw new SecurityException("Expected exactly one APK signer, found " + signers.size());
        }
        String actual = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(signers.get(0).getEncoded())
        );
        System.out.println("APK signer certificate SHA-256: " + actual);
        if (!actual.equalsIgnoreCase(args[1])) {
            throw new SecurityException("APK is not signed with the pinned certificate");
        }
        System.out.println("APK signature and pinned certificate verified.");
    }
}
