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
package onair.protocol;

import onair.crypto.Aes256Cipher;
import onair.crypto.EncryptionSettings;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Optional;

/**
 * Binary framing of the ONAIR protocol.
 * Format: [ONAIR][Length][Flags][Payload]
 * Payload, plaintext or encrypted (AES-256-GCM): Recipient, Sender, Message.
 */
public final class OnAirProtocol {

    public static final String MAGIC = "ONAIR";
    public static final byte[] MAGIC_BYTES = MAGIC.getBytes(StandardCharsets.US_ASCII);

    public static final byte FLAG_PLAIN = 0x00;
    public static final byte FLAG_ENCRYPTED = 0x01;

    private OnAirProtocol() {
    }

    public static byte[] encode(String recipient, String sender, String message, EncryptionSettings encryption) {
        encryption.validateForTransmit();
        byte[] innerPayload = encodeInnerPayload(recipient, sender, message);
        byte[] payloadBody;
        byte flags;

        if (encryption.isEncrypted()) {
            try {
                byte[] encrypted = Aes256Cipher.encrypt(innerPayload, encryption.getKey());
                payloadBody = new byte[2 + encrypted.length];
                ByteBuffer body = ByteBuffer.wrap(payloadBody);
                body.putShort((short) encrypted.length);
                body.put(encrypted);
                flags = FLAG_ENCRYPTED;
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("AES-256 encryption failed.", ex);
            }
        } else {
            payloadBody = innerPayload;
            flags = FLAG_PLAIN;
        }

        int payloadLength = 1 + payloadBody.length;
        ByteBuffer buffer = ByteBuffer.allocate(MAGIC_BYTES.length + 2 + payloadLength);
        buffer.put(MAGIC_BYTES);
        buffer.putShort((short) payloadLength);
        buffer.put(flags);
        buffer.put(payloadBody);
        return buffer.array();
    }

    public static Optional<OnAirFrame> tryDecode(byte[] data, int offset, int length, EncryptionSettings encryption) {
        if (length < MAGIC_BYTES.length + 2) {
            return Optional.empty();
        }

        for (int i = offset; i <= offset + length - MAGIC_BYTES.length; i++) {
            if (!matchesMagic(data, i)) {
                continue;
            }
            Optional<OnAirFrame> frame = parseFrame(data, i, offset + length, encryption);
            if (frame.isPresent()) {
                return frame;
            }
        }
        return Optional.empty();
    }

    public static Optional<OnAirFrame> parseFrame(byte[] data, int magicIndex, int endExclusive, EncryptionSettings encryption) {
        int cursor = magicIndex + MAGIC_BYTES.length;
        if (cursor + 2 > endExclusive) {
            return Optional.empty();
        }

        int payloadLength = ((data[cursor] & 0xFF) << 8) | (data[cursor + 1] & 0xFF);
        cursor += 2;

        if (payloadLength <= 0 || cursor + payloadLength > endExclusive) {
            return Optional.empty();
        }

        int payloadEnd = cursor + payloadLength;
        if (cursor >= payloadEnd) {
            return Optional.empty();
        }

        byte flags = data[cursor++];
        byte[] innerPayload;

        if (flags == FLAG_PLAIN) {
            innerPayload = copyRange(data, cursor, payloadEnd);
        } else if (flags == FLAG_ENCRYPTED) {
            if (encryption == null || encryption.getKey().isEmpty()) {
                return Optional.empty();
            }
            if (cursor + 2 > payloadEnd) {
                return Optional.empty();
            }
            int encryptedLength = ((data[cursor] & 0xFF) << 8) | (data[cursor + 1] & 0xFF);
            cursor += 2;
            if (encryptedLength <= 0 || cursor + encryptedLength > payloadEnd) {
                return Optional.empty();
            }
            byte[] encrypted = copyRange(data, cursor, cursor + encryptedLength);
            try {
                innerPayload = Aes256Cipher.decrypt(encrypted, encryption.getKey());
            } catch (GeneralSecurityException ex) {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }

        return decodeInnerPayload(innerPayload, magicIndex, payloadEnd);
    }

    private static byte[] encodeInnerPayload(String recipient, String sender, String message) {
        String safeRecipient = recipient == null ? "" : recipient.trim();
        String safeSender = sender == null ? "" : sender.trim();
        String safeMessage = message == null ? "" : message;

        byte[] recipientBytes = safeRecipient.getBytes(StandardCharsets.UTF_8);
        byte[] senderBytes = safeSender.getBytes(StandardCharsets.UTF_8);
        byte[] messageBytes = safeMessage.getBytes(StandardCharsets.UTF_8);

        if (recipientBytes.length > 255 || senderBytes.length > 255) {
            throw new IllegalArgumentException("Recipient or sender too long (max 255 bytes).");
        }
        if (messageBytes.length > 65535) {
            throw new IllegalArgumentException("Message too long (max 65535 bytes).");
        }

        ByteBuffer buffer = ByteBuffer.allocate(
                1 + recipientBytes.length + 1 + senderBytes.length + 2 + messageBytes.length);
        buffer.put((byte) recipientBytes.length);
        buffer.put(recipientBytes);
        buffer.put((byte) senderBytes.length);
        buffer.put(senderBytes);
        buffer.putShort((short) messageBytes.length);
        buffer.put(messageBytes);
        return buffer.array();
    }

    private static Optional<OnAirFrame> decodeInnerPayload(byte[] innerPayload, int startIndex, int endIndex) {
        int cursor = 0;
        int payloadEnd = innerPayload.length;

        if (cursor >= payloadEnd) {
            return Optional.empty();
        }

        int recipientLength = innerPayload[cursor++] & 0xFF;
        if (cursor + recipientLength > payloadEnd) {
            return Optional.empty();
        }
        String recipient = new String(innerPayload, cursor, recipientLength, StandardCharsets.UTF_8);
        cursor += recipientLength;

        if (cursor >= payloadEnd) {
            return Optional.empty();
        }
        int senderLength = innerPayload[cursor++] & 0xFF;
        if (cursor + senderLength > payloadEnd) {
            return Optional.empty();
        }
        String sender = new String(innerPayload, cursor, senderLength, StandardCharsets.UTF_8);
        cursor += senderLength;

        if (cursor + 2 > payloadEnd) {
            return Optional.empty();
        }
        int messageLength = ((innerPayload[cursor] & 0xFF) << 8) | (innerPayload[cursor + 1] & 0xFF);
        cursor += 2;
        if (cursor + messageLength > payloadEnd) {
            return Optional.empty();
        }
        String message = new String(innerPayload, cursor, messageLength, StandardCharsets.UTF_8);

        return Optional.of(new OnAirFrame(recipient, sender, message, startIndex, endIndex));
    }

    private static byte[] copyRange(byte[] data, int startInclusive, int endExclusive) {
        byte[] copy = new byte[endExclusive - startInclusive];
        System.arraycopy(data, startInclusive, copy, 0, copy.length);
        return copy;
    }

    private static boolean matchesMagic(byte[] data, int offset) {
        for (int i = 0; i < MAGIC_BYTES.length; i++) {
            if (data[offset + i] != MAGIC_BYTES[i]) {
                return false;
            }
        }
        return true;
    }

    public static final class OnAirFrame {
        private final String recipient;
        private final String sender;
        private final String message;
        private final int startIndex;
        private final int endIndex;

        public OnAirFrame(String recipient, String sender, String message, int startIndex, int endIndex) {
            this.recipient = recipient;
            this.sender = sender;
            this.message = message;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }

        public String getRecipient() {
            return recipient;
        }

        public String getSender() {
            return sender;
        }

        public String getMessage() {
            return message;
        }

        public int getStartIndex() {
            return startIndex;
        }

        public int getEndIndex() {
            return endIndex;
        }
    }
}
