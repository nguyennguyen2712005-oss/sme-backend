        package sme.backend.config;

        import com.fasterxml.jackson.annotation.JsonTypeInfo;
        import com.fasterxml.jackson.databind.ObjectMapper;
        import com.fasterxml.jackson.databind.SerializationFeature;
        import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
        import org.springframework.cache.annotation.EnableCaching;
        import org.springframework.context.annotation.Configuration;
        import org.springframework.context.annotation.Bean;
        import org.springframework.data.redis.cache.RedisCacheConfiguration;
        import org.springframework.data.redis.cache.RedisCacheManager;
        import org.springframework.data.redis.connection.RedisConnectionFactory;
        import org.springframework.data.redis.core.RedisTemplate;
        import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
        import org.springframework.data.redis.serializer.RedisSerializationContext;
        import org.springframework.data.redis.serializer.StringRedisSerializer;

        import java.time.Duration;

        @Configuration
        @EnableCaching
        public class RedisConfig {

        @Bean
        public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
                RedisTemplate<String, Object> template = new RedisTemplate<>();
                template.setConnectionFactory(factory);

                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());
                mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                mapper.activateDefaultTyping(
                        mapper.getPolymorphicTypeValidator(),
                        ObjectMapper.DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY
                );

                GenericJackson2JsonRedisSerializer jsonSerializer =
                        new GenericJackson2JsonRedisSerializer(mapper);

                template.setKeySerializer(new StringRedisSerializer());
                template.setHashKeySerializer(new StringRedisSerializer());
                template.setValueSerializer(jsonSerializer);
                template.setHashValueSerializer(jsonSerializer);
                template.afterPropertiesSet();
                return template;
        }

        @Bean
        public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());
                mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                mapper.activateDefaultTyping(
                        mapper.getPolymorphicTypeValidator(),
                        ObjectMapper.DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY
                );
                GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(mapper);

                RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofMinutes(30))
                        .serializeKeysWith(
                                RedisSerializationContext.SerializationPair
                                        .fromSerializer(new StringRedisSerializer()))
                        .serializeValuesWith(
                                RedisSerializationContext.SerializationPair
                                        .fromSerializer(jsonSerializer))
                        .disableCachingNullValues();

                return RedisCacheManager.builder(factory)
                        .cacheDefaults(defaultConfig)
                        // Cache riêng cho từng mục đích [cite: 1379]
                        .withCacheConfiguration("products",
                                defaultConfig.entryTtl(Duration.ofHours(1)))
                        .withCacheConfiguration("inventories",
                                defaultConfig.entryTtl(Duration.ofSeconds(30)))  
                        .withCacheConfiguration("customers",
                                defaultConfig.entryTtl(Duration.ofMinutes(15)))
                        .withCacheConfiguration("categories",
                                defaultConfig.entryTtl(Duration.ofHours(24)))
                        .build();
        }
        }