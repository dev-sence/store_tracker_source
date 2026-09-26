package sence.playf.storetracker.crypto;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * server/crypto.py의 SecureChannel과 완전히 동일한 알고리즘/파라미터로 구현해야
 * 서로 암복호화가 호환된다. (AES-256-GCM, PBKDF2HMAC-SHA256, salt="wnsghdev")
 */
public class SecureChannel {
    private static final byte[] SALT = "wnsghdev".getBytes(StandardCharsets.UTF_8);
    private static final int ITERATIONS = 200_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecureChannel(String passphrase) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), SALT, ITERATIONS, KEY_LENGTH_BITS);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            this.key = new SecretKeySpec(keyBytes, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("키 파생 실패", e);
        }
    }

    public String encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("암호화 실패", e);
        }
    }

    public byte[] decrypt(String payloadBase64) {
        try {
            byte[] raw = Base64.getDecoder().decode(payloadBase64);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
            byte[] ciphertext = new byte[raw.length - IV_LENGTH];
            System.arraycopy(raw, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("복호화 실패", e);
        }
    }

    public String encryptJson(JsonElement json) {
        return encrypt(json.toString().getBytes(StandardCharsets.UTF_8));
    }

    public JsonElement decryptJson(String payloadBase64) {
        return JsonParser.parseString(new String(decrypt(payloadBase64), StandardCharsets.UTF_8));
    }
}
