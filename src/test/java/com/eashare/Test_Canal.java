package com.eashare;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.Message;
import com.alibaba.otter.canal.protocol.CanalEntry;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Canal 连接测试类
 * 用于验证 Canal Server 连接和数据变更监听功能
 */
public class Test_Canal {

    private static final String CANAL_HOST = "127.0.0.1";
    private static final int CANAL_PORT = 11111;
    private static final String DESTINATION = "example";
    private static final String FILTER = "heimadianping\\..*";

    public static void main(String[] args) throws Exception {
        System.out.println("========== Canal 连接测试 ==========");
        System.out.println("Canal Server: " + CANAL_HOST + ":" + CANAL_PORT);
        System.out.println("Destination: " + DESTINATION);
        System.out.println("Filter: " + FILTER);
        System.out.println("====================================");

        CanalConnector connector = CanalConnectors.newSingleConnector(
                new InetSocketAddress(CANAL_HOST, CANAL_PORT),
                DESTINATION,
                "",
                ""
        );

        try {
            connector.connect();
            connector.subscribe(FILTER);
            connector.rollback();

            System.out.println("\n✓ 已成功连接到 Canal Server");
            System.out.println("✓ 开始监听数据变更...\n");

            int emptyCount = 0;
            while (true) {
                Message msg = connector.getWithoutAck(1000);

                if (msg.getId() == -1 || msg.getEntries().isEmpty()) {
                    emptyCount++;
                    if (emptyCount % 10 == 0) {
                        System.out.println("[心跳] 等待数据变更... (" + emptyCount + "s)");
                    }
                    Thread.sleep(1000);
                    continue;
                }

                emptyCount = 0;
                processMessage(msg);
            }

        } catch (Exception e) {
            System.err.println("\n✗ 发生错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            connector.disconnect();
            System.out.println("\n✓ 已断开连接");
        }
    }

    /**
     * 处理接收到的消息
     */
    private static void processMessage(Message msg) {
        System.out.println("\n========== 收到数据变更 ==========");
        System.out.println("批次ID: " + msg.getId());
        System.out.println("变更数量: " + msg.getEntries().size());
        System.out.println("==================================");

        for (CanalEntry.Entry entry : msg.getEntries()) {
            if (entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONBEGIN ||
                    entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONEND) {
                continue;
            }

            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }

            try {
                CanalEntry.RowChange change = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
                CanalEntry.EventType eventType = change.getEventType();
                String database = entry.getHeader().getSchemaName();
                String table = entry.getHeader().getTableName();

                System.out.println("\n📊 数据库: " + database);
                System.out.println("📋 表名: " + table);
                System.out.println("⚙️  操作: " + eventType);

                List<CanalEntry.RowData> rowDatasList = change.getRowDatasList();
                System.out.println("📝 影响行数: " + rowDatasList.size());

                for (int i = 0; i < rowDatasList.size(); i++) {
                    CanalEntry.RowData rowData = rowDatasList.get(i);
                    System.out.println("\n  --- 第 " + (i + 1) + " 行 ---");

                    if (eventType == CanalEntry.EventType.INSERT ||
                            eventType == CanalEntry.EventType.UPDATE) {
                        System.out.println("  【新数据】");
                        printColumns(rowData.getAfterColumnsList());
                    }

                    if (eventType == CanalEntry.EventType.DELETE ||
                            eventType == CanalEntry.EventType.UPDATE) {
                        System.out.println("  【旧数据】");
                        printColumns(rowData.getBeforeColumnsList());
                    }
                }
                System.out.println();

            } catch (Exception e) {
                System.err.println("解析失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * 打印列信息
     */
    private static void printColumns(List<CanalEntry.Column> columns) {
        for (CanalEntry.Column column : columns) {
            if (column.getIsKey()) {
                System.out.println("    🔑 " + column.getName() + " = " + column.getValue() + " (主键)");
            } else {
                System.out.println("    📌 " + column.getName() + " = " + column.getValue());
            }
        }
    }
}
