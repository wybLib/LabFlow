package org.example.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    public static final String LIKE_QUEUE = "like.queue";
    public static final String LIKE_EXCHANGE = "like.direct";
    public static final String LIKE_ROUTING_KEY = "like.routing.key";

    public static final String VIEW_EXCHANGE = "view.exchange";
    public static final String VIEW_QUEUE = "view.queue";
    public static final String VIEW_ROUTING_KEY = "view.routing.key";

    // 🚀 新增：缓存补偿相关的常量定义   删除操作用mq补偿机制
    public static final String CACHE_COMPENSATION_QUEUE = "cache.compensation.queue";
    public static final String CACHE_EXCHANGE = "cache.exchange";
    public static final String CACHE_DELETE_ROUTING_KEY = "cache.delete.routing.key";

    // ================== 评论相关配置 ==================
    public static final String COMMENT_INSERT_QUEUE = "comment.insert.queue"; // 异步落库队列
    public static final String COMMENT_FANOUT_EXCHANGE = "comment.fanout.exchange"; // 评论广播交换机

    // ai助手 Java (Spring Boot) 的角色是 生产者 Python (FastAPI / Worker) 的角色是 消费者
    public static final String VECTOR_SYNC_QUEUE = "VECTOR_SYNC_QUEUE"; // 必须和 Python worker 里定义的名字完全一致


    /**
     * 🚀 新增：专门为“批量落库”定制的监听器工厂
     */
    @Bean("batchQueueRabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory batchQueueRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);

        // 开启批量消费，设置批次大小为 100
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(100);

        return factory;
    }


    //----------------------------
    @Bean
    public Queue likeQueue() { return new Queue(LIKE_QUEUE, true); }
    @Bean
    public DirectExchange likeExchange() { return new DirectExchange(LIKE_EXCHANGE); }
    @Bean
    public Binding likeBinding(Queue likeQueue, DirectExchange likeExchange) {
        return BindingBuilder.bind(likeQueue).to(likeExchange).with(LIKE_ROUTING_KEY);
    }

    @Bean
    public Queue viewQueue() { return new Queue(VIEW_QUEUE, true); }
    @Bean
    public DirectExchange viewExchange() { return new DirectExchange(VIEW_EXCHANGE); }
    @Bean
    public Binding viewBinding(Queue viewQueue, DirectExchange viewExchange) {
        return BindingBuilder.bind(viewQueue).to(viewExchange).with(VIEW_ROUTING_KEY);
    }

    //----------------------------
    // 1. 声明缓存补偿队列
    @Bean
    public Queue cacheCompensationQueue() {
        return new Queue(CACHE_COMPENSATION_QUEUE, true); // 持久化队列
    }

    // 2. 声明缓存专用交换机
    @Bean
    public DirectExchange cacheExchange() {
        return new DirectExchange(CACHE_EXCHANGE);
    }

    // 3. 绑定队列到交换机
    @Bean
    public Binding cacheBinding(Queue cacheCompensationQueue, DirectExchange cacheExchange) {
        return BindingBuilder.bind(cacheCompensationQueue).to(cacheExchange).with(CACHE_DELETE_ROUTING_KEY);
    }
//----------------------------
    // 1. 异步落库专用队列 (持久化)
    @Bean
    public Queue commentInsertQueue() {
        return new Queue(COMMENT_INSERT_QUEUE, true);
    }

    // 2. 评论实时推送的广播交换机 (Fanout类型，发到这里的消息会被复制给所有绑定的队列)
    @Bean
    public FanoutExchange commentFanoutExchange() {
        return new FanoutExchange(COMMENT_FANOUT_EXCHANGE);
    }

    // 3. 广播接收队列 (极其关键：AnonymousQueue 会在每台服务器启动时自动生成一个随机名字的临时队列)
    @Bean
    public Queue commentBroadcastQueue() {
        return new AnonymousQueue();
    }

    // 4. 将本机的临时接收队列，绑定到广播交换机上
    @Bean
    public Binding commentBinding(Queue commentBroadcastQueue, FanoutExchange commentFanoutExchange) {
        return BindingBuilder.bind(commentBroadcastQueue).to(commentFanoutExchange);
    }

    /**
     * 配置 JSON 消息转换器，支持发送任何对象
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}