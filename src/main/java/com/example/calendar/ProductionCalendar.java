package com.example.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.Year;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Russian production calendar: weekends, federal holidays, transferred days off and working Saturdays.
 *
 * Data source: isdayoff.ru (one char per day of year: '0' — working day, '1' — day off).
 * Each year is cached in memory and refreshed weekly, since the calendar may be adjusted by decree.
 * If the API is unavailable or the year is not published yet, falls back to weekends + fixed federal holidays
 * (without transfers).
 */
public class ProductionCalendar {

    private static final Logger log = LoggerFactory.getLogger(ProductionCalendar.class);

    private static final String API_URL = "https://isdayoff.ru/api/getdata?cc=ru&year=";
    private static final Duration CACHE_TTL = Duration.ofDays(7);
    /** A real year has at least 104 weekend days; anything less means the year is not published. */
    private static final int MIN_DAYS_OFF = 104;

    private static final Set<MonthDay> FEDERAL_HOLIDAYS = Set.of(
            MonthDay.of(1, 1), MonthDay.of(1, 2), MonthDay.of(1, 3), MonthDay.of(1, 4),
            MonthDay.of(1, 5), MonthDay.of(1, 6), MonthDay.of(1, 7), MonthDay.of(1, 8),
            MonthDay.of(2, 23), MonthDay.of(3, 8), MonthDay.of(5, 1), MonthDay.of(5, 9),
            MonthDay.of(6, 12), MonthDay.of(11, 4)
    );

    private record YearData(String days, Instant loadedAt) {}

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Map<Integer, YearData> cache = new ConcurrentHashMap<>();

    public boolean isWorkingDay(LocalDate date) {
        String days = yearData(date.getYear());
        if (days != null) {
            return days.charAt(date.getDayOfYear() - 1) == '0';
        }
        return !isWeekend(date) && !FEDERAL_HOLIDAYS.contains(MonthDay.from(date));
    }

    private String yearData(int year) {
        YearData cached = cache.get(year);
        if (cached != null && cached.loadedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.days();
        }
        String fresh = fetch(year);
        if (fresh != null) {
            cache.put(year, new YearData(fresh, Instant.now()));
            return fresh;
        }
        // Keep using stale data rather than the rough fallback
        return cached != null ? cached.days() : null;
    }

    private String fetch(int year) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL + year))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body().trim();

            boolean valid = response.statusCode() == 200
                    && body.length() == Year.of(year).length()
                    && body.chars().allMatch(c -> c == '0' || c == '1')
                    && body.chars().filter(c -> c == '1').count() >= MIN_DAYS_OFF;
            if (!valid) {
                log.warn("Production calendar for {} is not available (HTTP {}), using fallback",
                        year, response.statusCode());
                return null;
            }
            log.info("Production calendar for {} loaded", year);
            return body;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed to load production calendar for {}, using fallback: {}", year, e.toString());
            return null;
        }
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }
}
