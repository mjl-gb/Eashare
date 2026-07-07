package com.eashare.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String SECKILL_QUEUE = "seckill.order.queue";
    public static final String SECKILL_EXCHANGE = "seckill.order.exchange";
    public static final String SECKILL_ROUTING_KEY = "seckill.order";
    
    // 死信队列配置
    public static final String SECKILL_DLX_QUEUE = "seckill.order.dlx.queue";
    public static final String SECKILL_DLX_EXCHANGE = "seckill.order.dlx.exchange";
    public static final String SECKILL_DLX_ROUTING_KEY = "seckill.order.dlx";

    // Canal 缓存删除队列配置
    public static final String CANAL_CACHE_DELETE_QUEUE = "canal.cache.delete.queue";
    public static final String CANAL_CACHE_DELETE_EXCHANGE = "canal.cache.delete.exchange";
    public static final String CANAL_CACHE_DELETE_ROUTING_KEY = "canal.cache.delete";

    @Bean
    public Queue seckillQueue() {
        // 配置死信交换机和路由键
        return QueueBuilder.durable(SECKILL_QUEUE)
                .deadLetterExchange(SECKILL_DLX_EXCHANGE)
                .deadLetterRoutingKey(SECKILL_DLX_ROUTING_KEY)
                .build();
    }

    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SECKILL_EXCHANGE, true, false);
    }

    @Bean
    public Binding seckillBinding(Queue seckillQueue, DirectExchange seckillExchange) {
        return BindingBuilder.bind(seckillQueue).to(seckillExchange).with(SECKILL_ROUTING_KEY);
    }
    
    // 死信队列相关配置
    @Bean
    public Queue seckillDlxQueue() {
        return new Queue(SECKILL_DLX_QUEUE, true, false, false);
    }

    @Bean
    public DirectExchange seckillDlxExchange() {
        return new DirectExchange(SECKILL_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Binding seckillDlxBinding(Queue seckillDlxQueue, DirectExchange seckillDlxExchange) {
        return BindingBuilder.bind(seckillDlxQueue).to(seckillDlxExchange).with(SECKILL_DLX_ROUTING_KEY);
    }

    // Canal 缓存删除队列相关配置
    @Bean
    public Queue canalCacheDeleteQueue() {
        return new Queue(CANAL_CACHE_DELETE_QUEUE, true, false, false);
    }

    @Bean
    public TopicExchange canalCacheDeleteExchange() {
        return new TopicExchange(CANAL_CACHE_DELETE_EXCHANGE, true, false);
    }

    @Bean
    public Binding canalCacheDeleteBinding(Queue canalCacheDeleteQueue, TopicExchange canalCacheDeleteExchange) {
        return BindingBuilder.bind(canalCacheDeleteQueue).to(canalCacheDeleteExchange).with(CANAL_CACHE_DELETE_ROUTING_KEY);
    }
}
