-- Real-Time Pipeline Metrics Queries
-- Use these in ClickHouse client or Grafana/Superset

-- 1. Basic Throughput (Events per minute over last hour)
SELECT 
    toStartOfMinute(created_at) as minute,
    COUNT(*) as events_per_minute,
    COUNT(DISTINCT user_id) as unique_users_per_minute
FROM rt.clicks_raw 
WHERE created_at >= now() - INTERVAL 1 HOUR
GROUP BY minute
ORDER BY minute DESC;

-- 2. End-to-End Latency Analysis (Last 5 minutes)
SELECT 
    AVG((toUnixTimestamp(created_at) - ts/1000) * 1000) as avg_latency_ms,
    quantile(0.95)((toUnixTimestamp(created_at) - ts/1000) * 1000) as p95_latency_ms,
    quantile(0.99)((toUnixTimestamp(created_at) - ts/1000) * 1000) as p99_latency_ms,
    MAX((toUnixTimestamp(created_at) - ts/1000) * 1000) as max_latency_ms,
    COUNT(*) as total_events
FROM rt.clicks_raw 
WHERE created_at >= now() - INTERVAL 5 MINUTE;

-- 3. Real-Time Data Growth (Rows per minute)
SELECT 
    toStartOfMinute(created_at) as minute,
    COUNT(*) as rows_inserted
FROM rt.clicks_raw 
WHERE created_at >= now() - INTERVAL 10 MINUTE
GROUP BY minute
ORDER BY minute DESC;

-- 4. Data Freshness Check (How recent is our data?)
SELECT 
    MIN(created_at) as oldest_data,
    MAX(created_at) as newest_data,
    MAX(created_at) - MIN(created_at) as data_span,
    now() - MAX(created_at) as data_lag_seconds
FROM rt.clicks_raw 
WHERE created_at >= now() - INTERVAL 1 HOUR;

-- 5. Pipeline Health Check (Events vs Aggregations)
SELECT 
    'raw_events' as table_name,
    COUNT(*) as row_count,
    MAX(created_at) as latest_timestamp
FROM rt.clicks_raw 
WHERE created_at >= now() - INTERVAL 5 MINUTE

UNION ALL

SELECT 
    'aggregations' as table_name,
    COUNT(*) as row_count,
    MAX(window_end) as latest_timestamp
FROM rt.page_minute_agg 
WHERE window_end >= now() - INTERVAL 5 MINUTE;

-- 6. Throughput Summary (Last 5 minutes)
SELECT 
    COUNT(*) / 5 as avg_events_per_minute,
    COUNT(*) / 300 as avg_events_per_second,
    COUNT(DISTINCT user_id) as unique_users,
    COUNT(DISTINCT page) as unique_pages,
    COUNT(DISTINCT country) as unique_countries
FROM rt.clicks_raw 
WHERE created_at >= now() - INTERVAL 5 MINUTE;