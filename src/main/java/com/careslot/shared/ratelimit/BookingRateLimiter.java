package com.careslot.shared.ratelimit;

import com.careslot.shared.config.CareSlotProperties;
import com.careslot.shared.error.RateLimitedException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Fixed one-minute window per patient in Redis, so a script hammering the booking API for one patient
 * (or a bot grabbing slots) is throttled across all app instances. If Redis is down the limiter lets the
 * request through: the database constraints still prevent double booking, the limiter is only a brake.
 */
@Component
public class BookingRateLimiter {

    private final StringRedisTemplate redis;
    private final int perMinute;

    BookingRateLimiter(StringRedisTemplate redis, CareSlotProperties props) {
        this.redis = redis;
        this.perMinute = props.booking().perPatientPerMinute();
    }

    public void check(UUID patientId) {
        long minute = Instant.now().getEpochSecond() / 60;
        String key = "rl:book:" + patientId + ":" + minute;
        Long n;
        try {
            n = redis.opsForValue().increment(key);
            if (n != null && n == 1) {
                redis.expire(key, Duration.ofSeconds(120));
            }
        } catch (RuntimeException e) {
            return;
        }
        if (n != null && n > perMinute) {
            throw new RateLimitedException("too many booking attempts for this patient", 60 - (int) (Instant.now().getEpochSecond() % 60));
        }
    }
}
