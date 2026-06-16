/*
 ONAIR - QAM Messenger
 Copyright (C) 2026  John Doe

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, version 3 of the License, GPL-3.0-only.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <https://www.gnu.org/licenses/>
*/
package onair.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption. The entered key is derived into 256 bits via SHA-256.
 */
public final class Aes256Cipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    private Aes256Cipher() {
    }

    public static byte[] encrypt(byte[] plaintext, String keyPhrase) throws GeneralSecurityException {
        if (plaintext == null) {
            throw new IllegalArgumentException("Missing data to encrypt.");
        }
        validateKeyPhrase(keyPhrase);

        byte[] iv = new byte[IV_LENGTH_BYTES];
        SecureRandom random = new SecureRandom();
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(keyPhrase), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        byte[] output = new byte[IV_LENGTH_BYTES + ciphertext.length];
        System.arraycopy(iv, 0, output, 0, IV_LENGTH_BYTES);
        System.arraycopy(ciphertext, 0, output, IV_LENGTH_BYTES, ciphertext.length);
        return output;
    }

    public static byte[] decrypt(byte[] encryptedPayload, String keyPhrase) throws GeneralSecurityException {
        if (encryptedPayload == null || encryptedPayload.length <= IV_LENGTH_BYTES) {
            throw new GeneralSecurityException("Invalid encrypted data.");
        }
        validateKeyPhrase(keyPhrase);

        byte[] iv = Arrays.copyOfRange(encryptedPayload, 0, IV_LENGTH_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(encryptedPayload, IV_LENGTH_BYTES, encryptedPayload.length);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(keyPhrase), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    public static void validateKeyPhrase(String keyPhrase) {
        if (keyPhrase == null || keyPhrase.isEmpty()) {
            throw new IllegalArgumentException("The AES-256 key is required when encryption is enabled.");
        }
    }

    private static SecretKeySpec deriveKey(String keyPhrase) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = digest.digest(keyPhrase.getBytes(StandardCharsets.UTF_8));
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new GeneralSecurityException("Invalid AES-256 key derivation.");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
