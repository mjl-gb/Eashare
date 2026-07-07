package com.eashare.config;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry.*;
import com.alibaba.otter.canal.protocol.Message;
import com.eashare.dto.CanalDataChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Canal 配置类
 * 用于连接 Canal Server，监听 MySQL binlog 变更并发送到 RabbitMQ
 */
@Slf4j
@Component
public class CanalConfig implements ApplicationRunner {

    @Value("${canal.server.host:127.0.0.1}")
    private String canalHost;

    @Value("${canal.server.port:11111}")
    private int canalPort;

    @Value("${canal.destination:example}")
    private String destination;

    @Value("${canal.filter:}")
    private String filter;

    @Resource
    private RabbitTemplate rabbitTemplate;

    private static final String CANAL_EXCHANGE = "canal.cache.delete.exchange";
    private static final String CANAL_ROUTING_KEY = "canal.cache.delete";

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 启动 Canal 监听线程
        Thread canalThread = new Thread(this::startCanalListener, "Canal-Listener");
        canalThread.setDaemon(true);
        canalThread.start();
        log.info("Canal 监听线程已启动");
    }

    /**
     * 启动 Canal 监听器
     */
    private void startCanalListener() {
        CanalConnector connector = null;
        try {
            // 创建 Canal 连接器
            connector = CanalConnectors.newSingleConnector(
                new InetSocketAddress(canalHost, canalPort),
                destination,
                "",
                ""
            );

            int batchSize = 1000;
            int emptyCount = 0;
            
            // 连接 Canal Server
            connector.connect();
            
            // 订阅过滤表达式
            if (filter != null && !filter.isEmpty()) {
                connector.subscribe(filter);
            } else {
                connector.subscribe();
            }
            
            // 回滚到未确认的位置，重新消费
            connector.rollback();

            log.info("========== Canal 监听器已启动 ==========");
            log.info("Canal Server: {}:{}", canalHost, canalPort);
            log.info("Destination: {}", destination);
            log.info("Filter: {}", filter);
            log.info("========================================");

            while (true) {
                // 获取指定数量的数据
                Message message = connector.getWithoutAck(batchSize);
                long batchId = message.getId();
                int size = message.getEntries().size();

                if (batchId == -1 || size == 0) {
                    emptyCount++;
                    if (emptyCount < 100) {
                        // 没有数据，休眠等待
                        Thread.sleep(1000);
                    } else {
                        // 连续多次没有数据，打印日志
                        log.debug("连续 {} 次未检测到数据变更", emptyCount);
                        emptyCount = 0;
                    }
                } else {
                    emptyCount = 0;
                    // 处理数据变更
                    processEntries(message.getEntries());
                }

                // 提交确认
                connector.ack(batchId);
            }

        } catch (Exception e) {
            log.error("Canal 监听器异常", e);
        } finally {
            if (connector != null) {
                try {
                    connector.disconnect();
                } catch (Exception e) {
                    log.error("断开 Canal 连接失败", e);
                }
            }
            log.warn("Canal 监听器已停止，5秒后尝试重启...");
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 递归调用，实现自动重启
            startCanalListener();
        }
    }

    /**
     * 处理数据变更条目
     */
    private void processEntries(List<Entry> entries) {
        for (Entry entry : entries) {
            // 只处理事务开始和结束之间的事件
            if (entry.getEntryType() == EntryType.TRANSACTIONBEGIN || 
                entry.getEntryType() == EntryType.TRANSACTIONEND) {
                continue;
            }

            // 只处理 ROWDATA 类型的事件
            if (entry.getEntryType() != EntryType.ROWDATA) {
                continue;
            }

            RowChange rowChange;
            try {
                rowChange = RowChange.parseFrom(entry.getStoreValue());
            } catch (Exception e) {
                log.error("解析 RowChange 失败", e);
                continue;
            }

            EventType eventType = rowChange.getEventType();
            String database = entry.getHeader().getSchemaName();
            String table = entry.getHeader().getTableName();

            log.debug("检测到数据变更 - 数据库: {}, 表: {}, 事件: {}", database, table, eventType);

            // 处理每一行数据
            for (RowData rowData : rowChange.getRowDatasList()) {
                CanalDataChangeEvent event = convertToEvent(database, table, eventType, rowData);
                if (event != null) {
                    sendToRabbitMQ(event);
                }
            }
        }
    }

    /**
     * 将 Canal 数据转换为自定义事件对象
     */
    private CanalDataChangeEvent convertToEvent(String database, String table, 
                                                 EventType eventType, RowData rowData) {
        CanalDataChangeEvent event = new CanalDataChangeEvent();
        event.setDatabase(database);
        event.setTable(table);
        event.setType(eventType.toString());

        Map<String, String> newData = new HashMap<>();
        Map<String, String> oldData = new HashMap<>();
        String pkId = null;

        // 处理新增或更新后的数据
        if (eventType == EventType.INSERT || eventType == EventType.UPDATE) {
            List<Column> columns = rowData.getAfterColumnsList();
            for (Column column : columns) {
                newData.put(column.getName(), column.getValue());
                // 假设第一个主键列是 ID
                if (column.getIsKey() && pkId == null) {
                    pkId = column.getValue();
                }
            }
        }

        // 处理删除或更新前的数据
        if (eventType == EventType.DELETE || eventType == EventType.UPDATE) {
            List<Column> columns = rowData.getBeforeColumnsList();
            for (Column column : columns) {
                oldData.put(column.getName(), column.getValue());
                // 假设第一个主键列是 ID
                if (column.getIsKey() && pkId == null) {
                    pkId = column.getValue();
                }
            }
        }

        event.setNewData(newData);
        event.setOldData(oldData);
        event.setPkId(pkId);

        return event;
    }

    /**
     * 发送事件到 RabbitMQ
     */
    private void sendToRabbitMQ(CanalDataChangeEvent event) {
        try {
            // 生成唯一的消息ID
            String messageId = UUID.randomUUID().toString();
            
            // 发送消息到 RabbitMQ
            rabbitTemplate.convertAndSend(CANAL_EXCHANGE, CANAL_ROUTING_KEY, event, message -> {
                message.getMessageProperties().setMessageId(messageId);
                return message;
            });
            
            log.info("发送 Canal 事件到 RabbitMQ - 表: {}, 事件: {}, ID: {}, 消息ID: {}", 
                event.getTable(), event.getType(), event.getPkId(), messageId);
            
        } catch (Exception e) {
            log.error("发送 Canal 事件到 RabbitMQ 失败", e);
        }
    }
}
