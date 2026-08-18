/*
 * Copyright (c) 2026 ECAT Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ecat.core.CommTrace.CommTraceBuffer;
import com.ecat.core.CommTrace.CommTraceDirection;
import com.ecat.core.CommTrace.CommTraceEvent;
import com.ecat.core.CommTrace.CommTraceFilter;
import com.ecat.core.CommTrace.CommTraceTransport;
import com.fazecast.jSerialComm.SerialPort;

/**
 * SerialSourcePort 通讯帧埋点测试：读路径（handleIncomingData）与写路径
 * （asyncSendData，mock SerialPort 走真实方法体）→ CommTraceBuffer 独立数据面。
 */
public class SerialSourcePortCommTraceTest {

    private static final String TEST_PORT = "/dev/tty-commtrace-test";

    private SerialSourcePort newPort() {
        return new SerialSourcePort(new SerialInfo(TEST_PORT, 9600, 8, 1, 0), 1, null);
    }

    @Test
    public void incomingDataCapturedAsRxFrame() {
        SerialSourcePort port = newPort();
        byte[] frame = new byte[]{0x01, 0x03, 0x02, 0x12, 0x34};
        long since = CommTraceBuffer.instance().latestSeq();

        port.handleIncomingData(frame, frame.length);

        List<CommTraceEvent> events = CommTraceBuffer.instance().query(
                new CommTraceFilter(CommTraceTransport.SERIAL, TEST_PORT, null, null, null), 10, since);
        assertEquals("读路径产生 1 帧 RX", 1, events.size());
        CommTraceEvent e = events.get(0);
        assertEquals(CommTraceDirection.RX, e.getDirection());
        assertEquals(5, e.getOriginalLength());
        assertEquals("01 03 02 12 34", e.renderHex());
    }

    @Test
    public void sendCapturedAsTxFrame() throws Exception {
        SerialSourcePort port = newPort();
        SerialPort serialPort = mock(SerialPort.class);
        when(serialPort.isOpen()).thenReturn(true);
        when(serialPort.bytesAvailable()).thenReturn(0); // test 模式写前清缓冲直接跳过
        when(serialPort.writeBytes(any(byte[].class), anyLong())).thenReturn(8);
        port.serialPort = serialPort;
        long since = CommTraceBuffer.instance().latestSeq();

        byte[] frame = new byte[]{0x01, 0x03, 0x00, 0x00, 0x00, 0x02, (byte) 0xC4, 0x0B};
        Boolean ok = port.asyncSendData(frame).get(10, TimeUnit.SECONDS);

        assertTrue("写成功", ok);
        List<CommTraceEvent> events = CommTraceBuffer.instance().query(
                new CommTraceFilter(CommTraceTransport.SERIAL, TEST_PORT, null, CommTraceDirection.TX, null),
                10, since);
        assertEquals("写路径产生 1 帧 TX", 1, events.size());
        assertEquals(8, events.get(0).getOriginalLength());
    }

    @Test
    public void writeFailureCountsChannelErrorNotTxFrame() throws Exception {
        SerialSourcePort port = newPort();
        SerialPort serialPort = mock(SerialPort.class);
        when(serialPort.isOpen()).thenReturn(true);
        when(serialPort.bytesAvailable()).thenReturn(0);
        when(serialPort.writeBytes(any(byte[].class), anyLong())).thenReturn(-1); // 非阻塞反压
        port.serialPort = serialPort;
        long since = CommTraceBuffer.instance().latestSeq();

        try {
            port.asyncSendData(new byte[]{0x01}).get(10, TimeUnit.SECONDS);
            throw new AssertionError("写失败须抛 SerialWriteException");
        } catch (java.util.concurrent.ExecutionException expected) {
            // SerialWriteException 透传
        }

        List<CommTraceEvent> tx = CommTraceBuffer.instance().query(
                new CommTraceFilter(CommTraceTransport.SERIAL, TEST_PORT, null, CommTraceDirection.TX, null),
                10, since);
        assertEquals("写失败不产生 TX 帧", 0, tx.size());
    }
}
