package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fazecast.jSerialComm.SerialPort;
import org.junit.Test;

/**
 * 【阶段② reconfigure 入队验收】applyReconfiguredSettings 物理参数变化分支的命令队列
 * 语义（Actor 事务准入设计 §3/S4，决策 D5 选项 a）：
 * <ol>
 *   <li>顺序性：在飞事务未完成（锁未释放）时重建命令只入队等待，close+reopen 必须发生在
 *       事务释放之后——历史事故形态（reconfigure 线程无锁协调 close 物理口）的正面验收；</li>
 *   <li>有界等待超时=显式失败上抛 {@link SerialReconfigureException}（不吞、不猜），
 *       端口状态原样未动、无队列残留、无幽灵锁；</li>
 *   <li>timeout-only 分支保持原地替换快路径：在飞事务期间也立即生效、不重建端口
 *       （回归护栏，与 {@link SerialIntegrationReconfigureSettingsTest} 静态形态互补）。</li>
 * </ol>
 *
 * <p>mock 手法参照 SerialReconfigureRaceTest / SerialIntegrationReconfigureSettingsTest：
 * 直接构造共享端口注入 mock 串口（isOpen 跟随 closePort 翻转），reopen 的换新对象经真实
 * {@code SerialPort.getCommPort("/dev/null")} 产生（存在文件返回对象、open 失败不抛），
 * 「serialPort 不再是旧 mock」即证明重建路径已执行。
 */
public class SerialReconfigureQueueWaitTest {

    private static final String PORT = "/dev/null";

    private static SerialSourcePort portWithOpenMock(SerialIntegration integration, SerialInfo info,
                                                     AtomicBoolean closedFlag) {
        SerialSourcePort port = new SerialSourcePort(info, 8, integration);
        SerialPort serialPort = mock(SerialPort.class);
        final boolean[] open = {true};
        when(serialPort.isOpen()).thenAnswer(inv -> open[0]);
        doAnswer(inv -> {
            open[0] = false;
            if (closedFlag != null) {
                closedFlag.set(true);
            }
            return true;
        }).when(serialPort).closePort();
        port.serialPort = serialPort;
        integration.serialPortsPutForTest(PORT, port);
        return port;
    }

    /**
     * 顺序性验收：在飞事务持锁期间，物理参数变化的 reconfigure 只入队等待
     * （closePort 零调用、队列深度=1、applyReconfiguredSettings 未返回）；
     * release 后重建才执行（close+reopen、新参数生效、无幽灵锁）。
     */
    @Test
    public void physicalReconfigureWaitsForInflightTransactionBeforeCloseReopen() throws Exception {
        SerialIntegration integration = new SerialIntegration();
        AtomicBoolean closed = new AtomicBoolean(false);
        SerialSourcePort port = portWithOpenMock(integration,
                new SerialInfo(PORT, 9600, 8, 1, 0), closed);
        SerialPort originalSerialPort = port.serialPort;

        // 在飞事务：持锁未释放
        String key = port.acquire();
        assertNotNull("测试前置：取得事务锁", key);

        // 配置流线程：提交重建命令（应阻塞等待，不得 close）
        CountDownLatch reconfigureDone = new CountDownLatch(1);
        final AtomicBoolean returnedNormally = new AtomicBoolean(false);
        Thread configThread = new Thread(() -> {
            try {
                port.applyReconfiguredSettings(new SerialInfo(PORT, 19200, 8, 1, 0), "reconfigure-test");
                returnedNormally.set(true);
            } finally {
                reconfigureDone.countDown();
            }
        });
        configThread.start();

        // 等待窗口（有界负断言）：在飞事务未完成期间，重建命令在队、close 零调用、未返回
        assertFalse("在飞事务未完成时 reconfigure 不得完成（应排队等待）",
                reconfigureDone.await(300, TimeUnit.MILLISECONDS));
        assertTrue("重建命令应在命令队列中排队", port.queuedConversations() == 1);
        assertTrue("在飞事务仍持有执行权", port.hasInFlightConversation());
        assertFalse("在飞事务未完成时不得 close 端口", closed.get());
        verify(originalSerialPort, never()).closePort();

        // 事务完成（release）→ 重建获得执行权 → close+reopen
        port.release(key);
        assertTrue("事务释放后 reconfigure 应完成重建", reconfigureDone.await(5, TimeUnit.SECONDS));
        configThread.join(5000);
        assertTrue("重建必须 close 旧端口（且仅在事务释放之后）", closed.get());
        verify(originalSerialPort).closePort();
        assertEquals("重建后端口必须持有新 baudrate", Integer.valueOf(19200), port.serialInfo.baudrate);
        assertNotSame("物理参数变化后必须 reopen（serialPort 不得仍是旧对象）", originalSerialPort, port.serialPort);
        assertTrue("重建命令完成后队列应清空", port.queuedConversations() == 0);

        // 无幽灵锁：重建 release 后新 acquire 立即授予
        String nextKey = port.acquire(2, TimeUnit.SECONDS, null);
        assertNotNull("重建完成后锁应立即可授予（无幽灵锁）", nextKey);
        port.release(nextKey);
        assertTrue("reconfigure 线程应正常返回（无异常）", returnedNormally.get());
    }

    /**
     * 有界等待超时=显式失败：预算耗尽抛 SerialReconfigureException，端口状态原样未动
     * （未 close、serialInfo 未替换）、超时命令自摘无队列残留、无幽灵锁。
     */
    @Test
    public void waitBudgetExhaustionThrowsExplicitFailureLeavesPortUntouched() throws Exception {
        SerialIntegration integration = new SerialIntegration();
        AtomicBoolean closed = new AtomicBoolean(false);
        SerialSourcePort port = portWithOpenMock(integration,
                new SerialInfo(PORT, 9600, 8, 1, 0), closed);
        port.setReconfigureRebuildWaitMsForTest(150);

        // 在飞事务持锁线程（与配置流线程异线程——真实形态，同线程嵌套守卫的正当边界）
        final CountDownLatch txHeld = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> txKey = new java.util.concurrent.atomic.AtomicReference<>();
        Thread txHolder = new Thread(() -> {
            String key = port.acquire();
            assertNotNull(key);
            txKey.set(key);
            txHeld.countDown();
        });
        txHolder.start();
        assertTrue(txHeld.await(5, TimeUnit.SECONDS));

        try {
            port.applyReconfiguredSettings(new SerialInfo(PORT, 19200, 8, 1, 0), "reconfigure-test");
            fail("在飞事务长期未释放时，重建等待超时必须显式抛 SerialReconfigureException");
        } catch (SerialReconfigureException expected) {
            // 显式失败：异常消息可定位（含端口与预算）
            assertTrue(expected.getMessage().contains(PORT));
        }

        // 端口状态原样未动 + 无队列残留
        assertFalse("超时失败不得 close 端口", closed.get());
        assertEquals("超时失败不得替换 serialInfo", Integer.valueOf(9600), port.serialInfo.baudrate);
        assertEquals("超时的重建命令应自摘离队（无死命令残留）", 0, port.queuedConversations());

        // 无幽灵锁：release 后新 acquire 立即授予；重建可重试成功（显式失败不产生永久损伤）
        port.release(txKey.get());
        String nextKey = port.acquire(2, TimeUnit.SECONDS, null);
        assertNotNull(nextKey);
        port.release(nextKey);
        port.applyReconfiguredSettings(new SerialInfo(PORT, 19200, 8, 1, 0), "reconfigure-test");
        assertTrue("重试的重建命令应完成 close+reopen", closed.get());
        assertEquals(Integer.valueOf(19200), port.serialInfo.baudrate);
    }

    /**
     * timeout-only 回归：在飞事务持锁期间 timeout-only 变化仍走原地替换快路径——
     * 立即返回（不入队等待）、不 close、不重建端口对象。
     */
    @Test(timeout = 5000)
    public void timeoutOnlyChangeSkipsQueueAndRebuildEvenWhileTransactionInFlight() {
        SerialIntegration integration = new SerialIntegration();
        AtomicBoolean closed = new AtomicBoolean(false);
        SerialSourcePort port = portWithOpenMock(integration,
                new SerialInfo(PORT, 9600, 8, 1, 0, 0, 500), closed);
        SerialPort originalSerialPort = port.serialPort;

        String key = port.acquire();
        assertNotNull(key);

        // timeout-only：在飞事务期间也必须立即生效（若误入队会阻塞至 timeout 判红）
        port.applyReconfiguredSettings(new SerialInfo(PORT, 9600, 8, 1, 0, 0, 2000), "reconfigure-test");

        assertEquals("timeout-only 变化必须即时生效", 2000, port.getTimeout());
        assertFalse("timeout-only 不得 close 端口", closed.get());
        assertSame("timeout-only 不得重建端口对象", originalSerialPort, port.serialPort);
        assertEquals("timeout-only 不入命令队列", 0, port.queuedConversations());

        port.release(key);
    }
}
