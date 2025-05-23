/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.underfs.tos;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import alluxio.conf.AlluxioConfiguration;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.retry.CountingRetry;

import com.volcengine.tos.TOSV2;
import com.volcengine.tos.model.object.GetObjectV2Input;
import com.volcengine.tos.model.object.GetObjectV2Output;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Arrays;

/**
 * Unit tests for the {@link TOSInputStream}.
 */
public class TOSInputStreamTest {

  private static final String BUCKET_NAME = "testBucket";
  private static final String OBJECT_KEY = "testObjectKey";
  private static AlluxioConfiguration sConf = Configuration.global();

  private TOSInputStream mTosInputStream;
  private TOSV2 mTosClient;
  private InputStream[] mInputStreamSpy;
  private GetObjectV2Output[] mTosObject;

  /**
   * The exception expected to be thrown.
   */
  @Rule
  public final ExpectedException mExceptionRule = ExpectedException.none();

  @Before
  public void setUp() throws IOException {
    mTosClient = mock(TOSV2.class);

    byte[] input = new byte[] {1, 2, 3};
    mTosObject = new GetObjectV2Output[input.length];
    mInputStreamSpy = new InputStream[input.length];
    for (int i = 0; i < input.length; ++i) {
      final long pos = (long) i;
      mTosObject[i] = mock(GetObjectV2Output.class);
      when(mTosClient.getObject(argThat(argument -> {
        if (argument instanceof GetObjectV2Input) {
          String range = ((GetObjectV2Input) argument).getOptions().getRange();
          return range.equals(MessageFormat.format("bytes={0}-", pos));
        }
        return false;
      }))).thenReturn(mTosObject[i]);
      byte[] mockInput = Arrays.copyOfRange(input, i, input.length);
      mInputStreamSpy[i] = spy(new ByteArrayInputStream(mockInput));
      when(mTosObject[i].getContent()).thenReturn(mInputStreamSpy[i]);
    }
    mTosInputStream = new TOSInputStream(BUCKET_NAME, OBJECT_KEY, mTosClient, new CountingRetry(1),
      sConf.getBytes(PropertyKey.UNDERFS_OBJECT_STORE_MULTI_RANGE_CHUNK_SIZE));
  }

  @Test
  public void close() throws IOException {
    mTosInputStream.close();

    mExceptionRule.expect(IOException.class);
    mExceptionRule.expectMessage(is("Stream closed"));
    mTosInputStream.read();
  }

  @Test
  public void readInt() throws IOException {
    assertEquals(1, mTosInputStream.read());
    assertEquals(2, mTosInputStream.read());
    assertEquals(3, mTosInputStream.read());
  }

  @Test
  public void readByteArray() throws IOException {
    byte[] bytes = new byte[3];
    int readCount = mTosInputStream.read(bytes, 0, 3);
    assertEquals(3, readCount);
    assertArrayEquals(new byte[] {1, 2, 3}, bytes);
  }

  @Test
  public void skip() throws IOException {
    assertEquals(1, mTosInputStream.read());
    mTosInputStream.skip(1);
    assertEquals(3, mTosInputStream.read());
  }
}
