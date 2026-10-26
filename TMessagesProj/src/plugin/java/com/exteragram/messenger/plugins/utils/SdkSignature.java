package com.exteragram.messenger.plugins.utils;

import android.text.TextUtils;

import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;

/**
 * Verifies downloaded Python SDK archives against the release key.
 *
 * <p>The signed payload is {@code extera-pysdk|<version>|<abi>|<sha256 of the archive>} and the
 * signature is a base64 ECDSA (P-256, SHA-256) block produced over those UTF-8 bytes. Verification
 * only runs when a public key is compiled in: with {@link #PUBLIC_KEY_BASE64} empty the updater
 * behaves exactly as before, so the key can be provisioned together with the release pipeline.
 */
public final class SdkSignature {

    private static final String PAYLOAD_PREFIX = "extera-pysdk";

    /**
     * Base64 of the DER-encoded {@code SubjectPublicKeyInfo} of the release ECDSA P-256 key.
     * Generate it with {@code Tools/sign-plugin-sdk.ps1 -KeyFile <private.pem> -PrintPublicKey}.
     */
    private static final String PUBLIC_KEY_BASE64 = "";

    private SdkSignature() {
    }

    /** True when a release key is compiled in, i.e. downloaded archives must be signed. */
    public static boolean isConfigured() {
        return !TextUtils.isEmpty(PUBLIC_KEY_BASE64);
    }

    /** Payload the release side signs; exposed so tooling and tests can reproduce it. */
    public static String payload(String version, String abi, String archiveSha256) {
        return PAYLOAD_PREFIX + "|" + (version == null ? "" : version) + "|" + (abi == null ? "" : abi)
                + "|" + (archiveSha256 == null ? "" : archiveSha256);
    }

    /**
     * True when {@code signatureBase64} is a valid signature of the archive for this version and
     * ABI. Returns false on any failure, including a missing signature while a key is configured.
     */
    public static boolean verify(String version, String abi, File archive, String signatureBase64) {
        if (!isConfigured()) {
            return true;
        }
        if (archive == null || !archive.exists() || TextUtils.isEmpty(signatureBase64)) {
            FileLog.e("Python SDK archive is not signed");
            return false;
        }
        try {
            final String digest = sha256Hex(archive);
            final byte[] payload = payload(version, abi, digest).getBytes("UTF-8");
            final byte[] signature = Base64.getDecoder().decode(signatureBase64.trim());
            final PublicKey key = KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_BASE64.trim())));
            final Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(payload);
            final boolean ok = verifier.verify(signature);
            if (!ok) {
                FileLog.e("Python SDK signature mismatch for " + version + "/" + abi);
            }
            return ok;
        } catch (Exception e) {
            FileLog.e("Failed to verify the Python SDK archive", e);
            return false;
        }
    }

    private static String sha256Hex(File file) throws Exception {
        final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream inputStream = new FileInputStream(file)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        final StringBuilder builder = new StringBuilder();
        for (byte b : digest.digest()) {
            builder.append(String.format(Locale.US, "%02x", b));
        }
        return builder.toString();
    }
}
