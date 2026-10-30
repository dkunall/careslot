package com.careslot.scheduling.adapter.cache;

import com.careslot.scheduling.application.AvailabilityCache;
import com.careslot.scheduling.domain.AvailabilityBitmap;
import com.careslot.shared.config.CareSlotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Bitmap per doctor per local day. Every Redis failure degrades to "not cached". */
@Component
class RedisAvailabilityCache implements AvailabilityCache {

    private static final Logger log = LoggerFactory.getLogger(RedisAvailabilityCache.class);

    private final RedisConnectionFactory redis;
    private final Duration ttl;

    RedisAvailabilityCache(RedisConnectionFactory redis, CareSlotProperties props) {
        this.redis = redis;
        this.ttl = props.availability().ttl();
    }

    static byte[] key(UUID practitionerId, LocalDate day) {
        return ("avail:" + practitionerId + ":" + day).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Optional<List<Instant>> get(UUID practitionerId, LocalDate day, ZoneId zone) {
        try (RedisConnection c = redis.getConnection()) {
            return AvailabilityBitmap.decode(day.atStartOfDay(zone).toInstant(),
                    c.stringCommands().get(key(practitionerId, day)));
        } catch (RuntimeException e) {
            log.debug("availability cache read failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(UUID practitionerId, LocalDate day, ZoneId zone, List<Instant> starts) {
        AvailabilityBitmap.encode(day.atStartOfDay(zone).toInstant(), starts).ifPresent(bits -> {
            try (RedisConnection c = redis.getConnection()) {
                c.stringCommands().set(key(practitionerId, day), bits, Expiration.from(ttl),
                        RedisStringCommands.SetOption.upsert());
            } catch (RuntimeException e) {
                log.debug("availability cache write failed: {}", e.toString());
            }
        });
    }

    @Override
    public void evict(UUID practitionerId, LocalDate day) {
        try (RedisConnection c = redis.getConnection()) {
            c.keyCommands().del(key(practitionerId, day));
        } catch (RuntimeException e) {
            log.warn("availability cache evict failed (entry expires within the TTL): {}", e.toString());
        }
    }
}
