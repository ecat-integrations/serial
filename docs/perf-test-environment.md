# 性能测试环境（20 对串口批量压测）

> 自 README 移出（2026-08-31 README 整改，43 号普查 §1.2 Q3）：运维压测脚本不进使用文档；日常上手用 README「本地测试环境」的单对 socat recipe。
> 注意：清理命令 `pkill -9 socat` 会杀掉宿主机上**所有** socat 进程（含他人会话的虚拟串口对），仅在独占环境执行。

配套压测类：`src/test/java/com/ecat/integration/SerialIntegration/bytes/MultiPortConcurrencyByteTest.java`

```bash

# 循环创建20对串口对（V0↔V1 ~ V38↔V39）
for i in {0..38..2}; do
    # 直接用root权限执行socat（避免sudo分叉进程）
    sudo bash -c "socat -d -d pty,raw,echo=0,link=/dev/ttyV$i pty,raw,echo=0,link=/dev/ttyV$((i+1)) &"
done

# 批量赋予串口读写权限
sudo chmod 666 /dev/ttyV{0..39}

# 只统计socat核心进程数量（应该输出20）
ps -ef | grep "socat -d -d pty" | grep -v grep | wc -l

# 杀掉所有socat进程（包括sudo包装的）
sudo pkill -9 socat
# 清理残留的串口符号链接
sudo rm -f /dev/ttyV{0..39}
```
