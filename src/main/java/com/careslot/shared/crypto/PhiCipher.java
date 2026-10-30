package com.careslot.shared.crypto;

import com.careslot.shared.config.CareSlotProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Field-level encryption for patient identifiers and notes: AES-256-GCM, random 96-bit nonce, and the
 * column name as associated data so a ciphertext copied into another column fails to decrypt.
 * Layout: {@code 0x01 | nonce(12) | ciphertext+tag}. The blind index for exact-match lookups is an HMAC with
 * a key derived from the same secret. One key, no rotation yet: see README limitations.
 */
@Component
public class PhiCipher {

    private static final byte VERSION = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec aesKey;
    private final SecretKeySpec indexKey;

    public PhiCipher(CareSlotProperties props) {
        String encoded = props.phi() == null ? null : props.phi().key();
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("careslot.phi.key (CARESLOT_PHI_KEY) is required");
        }
        byte[] key = Base64.getDecoder().decode(encoded.strip());
        if (key.length != 32) {
            throw new IllegalStateException("careslot.phi.key must be 32 bytes, base64-encoded");
        }
        this.aesKey = new SecretKeySpec(key, "AES");
        this.indexKey = new SecretKeySpec(hmac(new SecretKeySpec(key, "HmacSHA256"),
                "careslot blind index v1".getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
    }

    public byte[] encrypt(String plain, String column) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, nonce));
            c.updateAAD(column.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + 12 + ct.length).put(VERSION).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(byte[] blob, String column) {
        if (blob == null) {
            return null;
        }
        if (blob.length < 1 + 12 + 16 || blob[0] != VERSION) {
            throw new IllegalStateException("unrecognised ciphertext");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, blob, 1, 12));
            c.updateAAD(column.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(blob, 13, blob.length - 13), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed for " + column, e);
        }
    }

    public byte[] blindIndex(String value) {
        return hmac(indexKey, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(SecretKeySpec key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
