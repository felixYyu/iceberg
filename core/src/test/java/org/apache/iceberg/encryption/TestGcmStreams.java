/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.encryption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import javax.crypto.AEADBadTagException;
import org.apache.iceberg.Files;
import org.apache.iceberg.io.PositionOutputStream;
import org.apache.iceberg.io.SeekableInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestGcmStreams {

  @TempDir private Path temp;

  @Test
  public void testEmptyFile() throws IOException {
    Random random = new Random();
    byte[] key = new byte[16];
    random.nextBytes(key);
    byte[] aadPrefix = new byte[16];
    random.nextBytes(aadPrefix);
    byte[] readBytes = new byte[1];

    File testFile = temp.resolve("test" + System.nanoTime()).toFile();

    AesGcmOutputFile encryptedFile =
        new AesGcmOutputFile(Files.localOutput(testFile), key, aadPrefix);
    PositionOutputStream encryptedStream = encryptedFile.createOrOverwrite();
    encryptedStream.close();

    AesGcmInputFile decryptedFile = new AesGcmInputFile(Files.localInput(testFile), key, aadPrefix);
    assertThat(decryptedFile.getLength()).isEqualTo(0);

    try (SeekableInputStream decryptedStream = decryptedFile.newStream()) {
      assertThat(decryptedStream.read(readBytes)).as("Read empty stream").isEqualTo(-1);
    }

    // check that the AAD is still verified, even for an empty file
    byte[] badAAD = Arrays.copyOf(aadPrefix, aadPrefix.length);
    badAAD[1] -= 1; // modify the AAD slightly
    AesGcmInputFile badAADFile = new AesGcmInputFile(Files.localInput(testFile), key, badAAD);
    assertThat(badAADFile.getLength()).isEqualTo(0);

    try (SeekableInputStream decryptedStream = badAADFile.newStream()) {
      assertThatThrownBy(() -> decryptedStream.read(readBytes))
          .isInstanceOf(RuntimeException.class)
          .hasCauseInstanceOf(AEADBadTagException.class)
          .hasMessageContaining("GCM tag check failed");
    }
  }

  @Test
  public void testAADValidation() throws IOException {
    Random random = new Random();
    byte[] key = new byte[16];
    random.nextBytes(key);
    byte[] aadPrefix = new byte[16];
    random.nextBytes(aadPrefix);
    byte[] content = new byte[Ciphers.PLAIN_BLOCK_SIZE / 2]; // half a block
    random.nextBytes(content);

    File testFile = temp.resolve("test" + System.nanoTime()).toFile();

    AesGcmOutputFile encryptedFile =
        new AesGcmOutputFile(Files.localOutput(testFile), key, aadPrefix);
    try (PositionOutputStream encryptedStream = encryptedFile.createOrOverwrite()) {
      encryptedStream.write(content);
    }

    // verify the data can be read correctly with the right AAD
    AesGcmInputFile decryptedFile = new AesGcmInputFile(Files.localInput(testFile), key, aadPrefix);
    assertThat(decryptedFile.getLength()).isEqualTo(content.length);

    try (SeekableInputStream decryptedStream = decryptedFile.newStream()) {
      byte[] readContent = new byte[Ciphers.PLAIN_BLOCK_SIZE];
      int bytesRead = decryptedStream.read(readContent);
      assertThat(bytesRead).as("Bytes read should match bytes written").isEqualTo(content.length);
      assertThat(ByteBuffer.wrap(readContent, 0, bytesRead)).isEqualTo(ByteBuffer.wrap(content));
    }

    // test with the wrong AAD
    byte[] badAAD = Arrays.copyOf(aadPrefix, aadPrefix.length);
    badAAD[1] -= 1; // modify the AAD slightly
    AesGcmInputFile badAADFile = new AesGcmInputFile(Files.localInput(testFile), key, badAAD);
    assertThat(badAADFile.getLength()).isEqualTo(content.length);

    try (SeekableInputStream decryptedStream = badAADFile.newStream()) {
      byte[] readContent = new byte[Ciphers.PLAIN_BLOCK_SIZE];
      assertThatThrownBy(() -> decryptedStream.read(readContent))
          .isInstanceOf(RuntimeException.class)
          .hasCauseInstanceOf(AEADBadTagException.class)
          .hasMessageContaining("GCM tag check failed");
    }

    // modify the file contents
    try (FileChannel out = FileChannel.open(testFile.toPath(), StandardOpenOption.WRITE)) {
      long lastTagPosition = testFile.length() - Ciphers.GCM_TAG_LENGTH;
      out.position(lastTagPosition);
      out.write(ByteBuffer.wrap(key)); // overwrite the tag with other random bytes (the key)
    }

    // read with the correct AAD and verify the tag check fails
    try (SeekableInputStream decryptedStream = decryptedFile.newStream()) {
      byte[] readContent = new byte[Ciphers.PLAIN_BLOCK_SIZE];
      assertThatThrownBy(() -> decryptedStream.read(readContent))
          .isInstanceOf(RuntimeException.class)
          .hasCauseInstanceOf(AEADBadTagException.class)
          .hasMessageContaining("GCM tag check failed");
    }
  }

  @Test
  public void testCorruptNonce() throws IOException {
    Random random = new Random();
    byte[] key = new byte[16];
    random.nextBytes(key);
    byte[] aadPrefix = new byte[16];
    random.nextBytes(aadPrefix);
    byte[] content = new byte[Ciphers.PLAIN_BLOCK_SIZE / 2]; // half a block
    random.nextBytes(content);

    File testFile = temp.resolve("test" + System.nanoTime()).toFile();

    AesGcmOutputFile encryptedFile =
        new AesGcmOutputFile(Files.localOutput(testFile), key, aadPrefix);
    try (PositionOutputStream encryptedStream = encryptedFile.createOrOverwrite()) {
      encryptedStream.write(content);
    }

    // verify the data can be read correctly with the right AAD
    AesGcmInputFile decryptedFile = new AesGcmInputFile(Files.localInput(testFile), key, aadPrefix);
    assertThat(decryptedFile.getLength()).isEqualTo(content.length);

    try (SeekableInputStream decryptedStream = decryptedFile.newStream()) {
      byte[] readContent = new byte[Ciphers.PLAIN_BLOCK_SIZE];
      int bytesRead = decryptedStream.read(readContent);
      assertThat(bytesRead).as("Bytes read should match bytes written").isEqualTo(content.length);
      assertThat(ByteBuffer.wrap(readContent, 0, bytesRead)).isEqualTo(ByteBuffer.wrap(content));
    }

    // replace the first block's nonce
    try (FileChannel out = FileChannel.open(testFile.toPath(), StandardOpenOption.WRITE)) {
      out.position(Ciphers.GCM_STREAM_HEADER_LENGTH);
      // overwrite the nonce with other random bytes (the key)
      out.write(ByteBuffer.wrap(key, 0, Ciphers.NONCE_LENGTH));
    }

    // read with the correct AAD and verify the read fails
    try (SeekableInputStream decryptedStream = decryptedFile.newStream()) {
      byte[] readContent = new byte[Ciphers.PLAIN_BLOCK_SIZE];
      assertThatThrownBy(() -> decryptedStream.read(readContent))
          .isInstanceOf(RuntimeException.class)
          .hasCauseInstanceOf(AEADBadTagException.class)
          .hasMessageContaining("GCM tag check failed");
    }
  }

  @Test
  public void testCorruptCiphertext() throws IOException {
    Random random = new Random();
    byte[] key = new byte[16];
    random.nextBytes(key);
    byte[] aadPrefix = new byte[16];
    random.nextBytes(aadPrefix);
    byte[] content = new byte[Ciphers.PLAIN_BLOCK_SIZE / 2]; // half a block
    random.nextBytes(content);

    File testFile = temp.resolve("test" + System.nanoTime()).toFile();

    AesGcmOutputFile encryptedFile =
        new AesGcmOutputFile(Files.localOutput(testFile), key, aadPrefix);
    try (PositionOutputStream encryptedStream = encryptedFile.createOrOverwrite()) {
      encryptedStream.write(content);
    }

    // verify the data can be read correctly with the right AAD
    AesGcmInputFile decryptedFile = new AesGcmInputFile(Files.localInput(testFile), key, aadPrefix);
    assertThat(decryptedFile.getLength()).isEqualTo(content.length);

    try (SeekableInputStream decryptedStream = decryptedFile.newStream()) {
      byte[] readContent = new byte[Ciphers.PLAIN_BLOCK_SIZE];
      int bytesRead = decryptedStream.read(readContent);
      assertThat(bytesRead).as("Bytes read should match bytes written").isEqualTo(content.length);
      assertThat(ByteBuffer.wrap(readContent, 0, bytesRead)).isEqualTo(ByteBuffer.wrap(content));
    }

    // replace part of the first block's content
    try (FileChannel out = FileChannel.open(testFile.toPath(), StandardOpenOption.WRITE)) {
      out.position(Ciphers.GCM_STREAM_HEADER_LENGTH + Ciphers.NONCE_LENGTH + 34);
      // overwrite the nonce with other random bytes (the key)
      out.write(ByteBuffer.wrap(key));
    }

    // read with the correct AAD and verify the read fails
    try (SeekableInputStream decryptedStream = decryptedFile.newStream()) {
      byte[] readContent = new byte[Ciphers.PLAIN_BLOCK_SIZE];
      assertThatThrownBy(() -> decryptedStream.read(readContent))
          .isInstanceOf(RuntimeException.class)
          .hasCauseInstanceOf(AEADBadTagException.class)
          .hasMessageContaining("GCM tag check failed");
    }
  }

  @Test
  public void testRandomWriteRead() throws IOException {
    Random random = new Random();
    int smallerThanBlock = (int) (Ciphers.PLAIN_BLOCK_SIZE * 0.5);
    int largerThanBlock = (int) (Ciphers.PLAIN_BLOCK_SIZE * 1.5);
    int alignedWithBlock = Ciphers.PLAIN_BLOCK_SIZE;
    int[] testFileSizes = {
      smallerThanBlock,
      largerThanBlock,
      alignedWithBlock,
      alignedWithBlock - 1,
      alignedWithBlock + 1
    };

    for (int testFileSize : testFileSizes) {
      byte[] testFileContents = new byte[testFileSize];
      random.nextBytes(testFileContents);
      int[] aesKeyLengthArray = {16, 24, 32};
      byte[] aadPrefix = new byte[16];
      for (int keyLength : aesKeyLengthArray) {
        byte[] key = new byte[keyLength];
        random.nextBytes(key);
        random.nextBytes(aadPrefix);
        File testFile = temp.resolve("test" + System.nanoTime()).toFile();

        AesGcmOutputFile encryptedFile =
            new AesGcmOutputFile(Files.localOutput(testFile), key, aadPrefix);
        PositionOutputStream encryptedStream = encryptedFile.createOrOverwrite();

        int maxChunkLen = testFileSize / 5;
        int offset = 0;
        int left = testFileSize;

        while (left > 0) {
          int chunkLen = random.nextInt(maxChunkLen);
          if (chunkLen > left) {
            chunkLen = left;
          }
          encryptedStream.write(testFileContents, offset, chunkLen);
          offset += chunkLen;
          assertThat(encryptedStream.getPos()).isEqualTo(offset);
          left -= chunkLen;
        }

        encryptedStream.close();
        assertThat(encryptedStream.getPos())
            .as("Final position in closed stream")
            .isEqualTo(offset);

        AesGcmInputFile decryptedFile =
            new AesGcmInputFile(Files.localInput(testFile), key, aadPrefix);
        SeekableInputStream decryptedStream = decryptedFile.newStream();
        assertThat(decryptedFile.getLength()).isEqualTo(testFileSize);

        byte[] chunk = new byte[testFileSize];

        // Test seek and read
        for (int n = 0; n < 100; n++) {
          int chunkLen = random.nextInt(testFileSize);
          int pos = random.nextInt(testFileSize);
          left = testFileSize - pos;

          if (left < chunkLen) {
            chunkLen = left;
          }

          decryptedStream.seek(pos);
          int len = decryptedStream.read(chunk, 0, chunkLen);
          assertThat(chunkLen).isEqualTo(len);
          long pos2 = decryptedStream.getPos();
          assertThat(pos2).isEqualTo(pos + len);

          ByteBuffer bb1 = ByteBuffer.wrap(chunk, 0, chunkLen);
          ByteBuffer bb2 = ByteBuffer.wrap(testFileContents, pos, chunkLen);
          assertThat(bb2).isEqualTo(bb1);

          // Test skip
          long toSkip = random.nextInt(testFileSize);
          long skipped = decryptedStream.skip(toSkip);

          if (pos2 + toSkip < testFileSize) {
            assertThat(skipped).isEqualTo(toSkip);
          } else {
            assertThat(skipped).isEqualTo(testFileSize - pos2);
          }

          int pos3 = (int) decryptedStream.getPos();
          assertThat(pos3).isEqualTo(pos2 + skipped);

          chunkLen = random.nextInt(testFileSize);
          left = testFileSize - pos3;

          if (left < chunkLen) {
            chunkLen = left;
          }

          decryptedStream.read(chunk, 0, chunkLen);
          bb1 = ByteBuffer.wrap(chunk, 0, chunkLen);
          bb2 = ByteBuffer.wrap(testFileContents, pos3, chunkLen);
          assertThat(bb2).isEqualTo(bb1);
        }

        decryptedStream.close();
      }
    }
  }

  @Test
  public void testAlignedWriteRead() throws IOException {
    Random random = new Random();
    int[] testFileSizes = {
      Ciphers.PLAIN_BLOCK_SIZE, Ciphers.PLAIN_BLOCK_SIZE + 1, Ciphers.PLAIN_BLOCK_SIZE - 1
    };

    for (int testFileSize : testFileSizes) {
      byte[] testFileContents = new byte[testFileSize];
      random.nextBytes(testFileContents);
      byte[] key = new byte[16];
      random.nextBytes(key);
      byte[] aadPrefix = new byte[16];
      random.nextBytes(aadPrefix);

      File testFile = temp.resolve("test" + System.nanoTime()).toFile();
      AesGcmOutputFile encryptedFile =
          new AesGcmOutputFile(Files.localOutput(testFile), key, aadPrefix);
      PositionOutputStream encryptedStream = encryptedFile.createOrOverwrite();

      int offset = 0;
      int chunkLen = Ciphers.PLAIN_BLOCK_SIZE;
      int left = testFileSize;

      while (left > 0) {

        if (chunkLen > left) {
          chunkLen = left;
        }

        encryptedStream.write(testFileContents, offset, chunkLen);
        offset += chunkLen;
        assertThat(encryptedStream.getPos()).isEqualTo(offset);
        left -= chunkLen;
      }

      encryptedStream.close();
      assertThat(encryptedStream.getPos()).as("Final position in closed stream").isEqualTo(offset);

      AesGcmInputFile decryptedFile =
          new AesGcmInputFile(Files.localInput(testFile), key, aadPrefix);
      SeekableInputStream decryptedStream = decryptedFile.newStream();
      assertThat(decryptedFile.getLength()).isEqualTo(testFileSize);

      offset = 0;
      chunkLen = Ciphers.PLAIN_BLOCK_SIZE;
      byte[] chunk = new byte[chunkLen];
      left = testFileSize;

      while (left > 0) {

        if (chunkLen > left) {
          chunkLen = left;
        }

        decryptedStream.seek(offset);
        int len = decryptedStream.read(chunk, 0, chunkLen);
        assertThat(chunkLen).isEqualTo(len);
        assertThat(decryptedStream.getPos()).isEqualTo(offset + len);

        ByteBuffer bb1 = ByteBuffer.wrap(chunk, 0, chunkLen);
        ByteBuffer bb2 = ByteBuffer.wrap(testFileContents, offset, chunkLen);
        assertThat(bb2).isEqualTo(bb1);

        offset += len;
        left = testFileSize - offset;
      }

      decryptedStream.close();
    }
  }
}
