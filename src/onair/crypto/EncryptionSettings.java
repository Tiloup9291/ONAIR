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

/**
 * Encryption settings configurable from the UI.
 */

public class EncryptionSettings {

    private boolean encrypted;
    private String key = "";

    public boolean isEncrypted() {
        return encrypted;
    }

    public void setEncrypted(boolean encrypted) {
        this.encrypted = encrypted;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key == null ? "" : key;
    }

    public void validateForTransmit() {
        if (encrypted) {
            Aes256Cipher.validateKeyPhrase(key);
        }
    }

    public EncryptionSettings copy() {
        EncryptionSettings copy = new EncryptionSettings();
        copy.encrypted = encrypted;
        copy.key = key;
        return copy;
    }
}
