-- Create the main database
CREATE DATABASE IF NOT EXISTS rt;

-- Raw clicks table for storing individual events
CREATE TABLE IF NOT EXISTS rt.clicks_raw
(
    event_id String,
    user_id String,
    ts DateTime64(3),
    page String,
    referrer String,
    country LowCardinality(String),
    device LowCardinality(String),
    created_at DateTime DEFAULT now()
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(ts)
ORDER BY (ts, user_id, event_id)
TTL toDateTime(ts) + INTERVAL 30 DAY DELETE
SETTINGS index_granularity = 8192;

-- Aggregated table for 1-minute windows by page and country
CREATE TABLE IF NOT EXISTS rt.page_minute_agg
(
    window_start DateTime,
    window_end DateTime,
    page String,
    country LowCardinality(String),
    cnt UInt64,
    unique_users UInt64,
    created_at DateTime DEFAULT now()
)
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(window_start)
ORDER BY (window_start, page, country)
TTL toDateTime(window_start) + INTERVAL 90 DAY DELETE
SETTINGS index_granularity = 8192;

-- Aggregated table for 5-minute windows by page
CREATE TABLE IF NOT EXISTS rt.page_5min_agg
(
    window_start DateTime,
    window_end DateTime,
    page String,
    cnt UInt64,
    unique_users UInt64,
    unique_countries UInt64,
    created_at DateTime DEFAULT now()
)
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(window_start)
ORDER BY (window_start, page)
TTL toDateTime(window_start) + INTERVAL 90 DAY DELETE
SETTINGS index_granularity = 8192;

-- Hourly aggregated table for long-term analytics
CREATE TABLE IF NOT EXISTS rt.page_hourly_agg
(
    window_start DateTime,
    window_end DateTime,
    page String,
    country LowCardinality(String),
    device LowCardinality(String),
    cnt UInt64,
    unique_users UInt64,
    created_at DateTime DEFAULT now()
)
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(window_start)
ORDER BY (window_start, page, country, device)
TTL toDateTime(window_start) + INTERVAL 1 YEAR DELETE
SETTINGS index_granularity = 8192;

-- Create materialized view for real-time dashboard queries
CREATE MATERIALIZED VIEW IF NOT EXISTS rt.page_stats_mv
TO rt.page_minute_agg
AS SELECT
    toStartOfMinute(ts) as window_start,
    toStartOfMinute(ts) + INTERVAL 1 MINUTE as window_end,
    page,
    country,
    count() as cnt,
    uniqExact(user_id) as unique_users
FROM rt.clicks_raw
GROUP BY window_start, window_end, page, country;

-- Create a view for recent activity (last 24 hours)
CREATE VIEW IF NOT EXISTS rt.recent_activity AS
SELECT 
    page,
    country,
    device,
    count() as total_clicks,
    uniqExact(user_id) as unique_users,
    max(ts) as last_seen
FROM rt.clicks_raw
WHERE ts >= now() - INTERVAL 24 HOUR
GROUP BY page, country, device
ORDER BY total_clicks DESC;

-- Create indexes for better query performance
-- Note: ClickHouse uses ORDER BY for primary index, but we can create secondary indexes

-- Index for user_id lookups
CREATE INDEX IF NOT EXISTS idx_user_id ON rt.clicks_raw (user_id) TYPE bloom_filter GRANULARITY 1;

-- Index for page lookups
CREATE INDEX IF NOT EXISTS idx_page ON rt.clicks_raw (page) TYPE bloom_filter GRANULARITY 1;