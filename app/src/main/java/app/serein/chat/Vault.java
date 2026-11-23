package app.serein.chat;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Vault {
    private static final String ALIAS = "serein.vault.v1";
    private final SharedPreferences prefs;

    Vault(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    private static synchronized SecretKey key(boolean create) throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        if (keys.getKey(ALIAS, null) instanceof SecretKey existing) return existing;
        if (!create) throw new IllegalStateException("Missing encryption key");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    String read(String slot, String fallback) {
        String value = prefs.getString("vault_" + slot, null);
        if (value == null) return fallback;
        try {
            byte[] packed = Base64.decode(value, Base64.NO_WRAP);
            byte[] clear = unseal(slot, packed);
            try { return new String(clear, StandardCharsets.UTF_8); }
            finally { Arrays.fill(clear, (byte) 0); }
        } catch (Exception error) {
            throw new IllegalStateException("无法解锁本机数据", error);
        }
    }

    void write(String slot, String value, String... legacyFields) {
        byte[] clear = value.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] packed = seal(slot, clear);
            SharedPreferences.Editor edit = prefs.edit().putString("vault_" + slot, Base64.encodeToString(packed, Base64.NO_WRAP));
            for (String legacy : legacyFields) edit.remove(legacy);
            if (!edit.commit()) throw new IllegalStateException("Vault write failed");
        } catch (Exception error) {
            throw new IllegalStateException("无法保存加密数据", error);
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    static byte[] seal(String slot, byte[] clear) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(slot.getBytes(StandardCharsets.UTF_8));
        byte[] nonce = cipher.getIV();
        if (nonce.length != 12) throw new IllegalStateException("Invalid nonce");
        byte[] ciphertext = cipher.doFinal(clear);
        byte[] packed = new byte[13 + ciphertext.length];
        packed[0] = 1;
        System.arraycopy(nonce, 0, packed, 1, 12);
        System.arraycopy(ciphertext, 0, packed, 13, ciphertext.length);
        return packed;
    }

    static byte[] unseal(String slot, byte[] packed) throws Exception {
        if (packed.length < 29 || packed[0] != 1) throw new IllegalStateException("Invalid vault record");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, packed, 1, 12));
        cipher.updateAAD(slot.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(packed, 13, packed.length - 13);
    }
}
