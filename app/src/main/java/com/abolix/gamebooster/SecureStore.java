package com.abolix.gamebooster;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM storage backed by Android Keystore for subscription URLs and proxy credentials. */
public final class SecureStore {
    private static final String PREFS = "abolix_secure_store";
    private static final String PREFIX = "enc:v1:";
    private static final String ALIAS = "abolix_subscription_aes_gcm_v1";

    private SecureStore() { }

    public static String get(Context context, String key, String fallback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(key, null);
        if (stored == null) return fallback;
        if (!stored.startsWith(PREFIX)) {
            // Migrate plaintext values from early development builds to Keystore encryption.
            put(context, key, stored);
            return stored;
        }
        try { return decrypt(stored); }
        catch (Exception e) { throw new IllegalStateException("بازکردن داده‌ی محافظت‌شده ناموفق بود", e); }
    }

    public static void put(Context context, String key, String value) {
        String encrypted = seal(value);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, encrypted).apply();
    }

    public static String seal(String value) {
        try { return encrypt(value == null ? "" : value); }
        catch (Exception e) { throw new IllegalStateException("ذخیره‌ی امن داده ناموفق بود", e); }
    }

    private static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] ciphertext = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        ByteBuffer payload = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext);
        return PREFIX + Base64.encodeToString(payload.array(), Base64.NO_WRAP);
    }

    private static String decrypt(String value) throws Exception {
        byte[] payload = Base64.decode(value.substring(PREFIX.length()), Base64.NO_WRAP);
        if (payload.length < 12 + 16) throw new IllegalArgumentException("داده‌ی رمزگذاری‌شده ناقص است");
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte[] iv = new byte[12]; buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()]; buffer.get(ciphertext);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
