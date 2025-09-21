# Real-Time Event Streaming - Monitoring & Analytics Setup Guide

## Quick Access

- **Grafana (System Monitoring)**: http://localhost:3000
  - Default login: admin/admin
  - Focus: Infrastructure, Flink metrics, system health

- **Superset (Business Intelligence)**: http://localhost:8088  
  - Default login: admin/admin
  - Focus: Business analytics, click event insights, real-time dashboards

- **Prometheus (Metrics)**: http://localhost:9090
  - Raw metrics and alerting backend

## Grafana Setup (Infrastructure Monitoring)

### 1. Login to Grafana
- Go to http://localhost:3000
- Login with admin/admin
- You'll be prompted to change password (optional)

### 2. Verify Data Source
- Go to Connections > Data Sources
- Prometheus should already be configured at http://prometheus:9090

### 3. Import Flink Dashboard
Create a new dashboard for Flink monitoring with these panels:

**Panel 1: Flink Job Status**
```
Query: up{job=~"flink.*"}
Visualization: Stat
Title: Flink Services Status
```

**Panel 2: Kafka Message Rate**
```
Query: rate(kafka_server_brokertopicmetrics_messagesin_total[5m])
Visualization: Time Series
Title: Kafka Messages/sec
```

**Panel 3: TaskManager Memory**
```
Query: flink_taskmanager_Status_JVM_Memory_Used
Visualization: Time Series  
Title: TaskManager Memory Usage
```

### 4. System Metrics Dashboard
Add panels for:
- CPU usage across containers
- Memory consumption
- Network I/O
- Disk usage

## Superset Setup (Business Intelligence)

### 1. Login to Superset
- Go to http://localhost:8088
- Login with admin/admin

### 2. Add ClickHouse Database Connection
1. Click Settings > Database Connections
2. Click + DATABASE
3. Select "ClickHouse" from the dropdown
4. Fill in connection details:
   ```
   Database name: realtime-events
   Host: clickhouse
   Port: 8123
   Database name: rt
   Username: default
   Password: (leave empty)
   ```
5. Click "Connect"

### 3. Create Datasets
Add these tables as datasets:

**Dataset 1: Raw Click Events**
- Table: rt.clicks_raw
- Use for: Real-time event monitoring, user behavior

**Dataset 2: Page Minute Aggregations**  
- Table: rt.page_minute_agg
- Use for: Traffic analysis, trending pages

### 4. Build Real-Time Dashboards

**Dashboard 1: Real-Time Traffic Overview**
- Total events (last hour)
- Events per minute (time series)
- Top pages by traffic
- Geographic distribution
- Device breakdown

**Dashboard 2: Page Performance Analytics**
- Page views over time
- Unique users per page
- Bounce rate analysis
- Country-wise traffic patterns

**Dashboard 3: Anomaly Detection**
- Traffic spikes detection
- Unusual user behavior
- System health indicators

## Sample Queries for Superset

### Real-Time Metrics (last 5 minutes)
```sql
SELECT 
    page,
    COUNT(*) as events,
    COUNT(DISTINCT user_id) as unique_users
FROM rt.clicks_raw 
WHERE ts >= now() - INTERVAL 5 MINUTE
GROUP BY page
ORDER BY events DESC
```

### Traffic Trends (last hour)
```sql
SELECT 
    toStartOfMinute(ts) as minute,
    COUNT(*) as events,
    COUNT(DISTINCT user_id) as unique_users
FROM rt.clicks_raw 
WHERE ts >= now() - INTERVAL 1 HOUR
GROUP BY minute
ORDER BY minute
```

### Geographic Analysis
```sql
SELECT 
    country,
    COUNT(*) as events,
    COUNT(DISTINCT user_id) as unique_users,
    AVG(if(referrer = '/', 0, 1)) as external_traffic_ratio
FROM rt.clicks_raw 
WHERE ts >= now() - INTERVAL 1 HOUR
GROUP BY country
ORDER BY events DESC
```

### Device & Platform Analytics
```sql
SELECT 
    device,
    COUNT(*) as events,
    COUNT(DISTINCT user_id) as unique_users,
    COUNT(DISTINCT page) as pages_visited
FROM rt.clicks_raw 
WHERE ts >= now() - INTERVAL 1 HOUR
GROUP BY device
ORDER BY events DESC
```

## Alerting Setup

### Grafana Alerts
Set up alerts for:
- Flink job failures
- High memory usage (>80%)
- Low throughput (<expected events/min)
- ClickHouse connection issues

### Example Alert Rule
```
Alert Name: Low Event Throughput
Query: rate(events_processed_total[5m]) < 10
Condition: Last value is below 10
Alert every: 1m
Message: "Event processing rate has dropped below 10 events/minute"
```

## 📱 Auto-Refresh & Real-Time Features

### Grafana Real-Time
- Set auto-refresh to 5s-30s for real-time monitoring
- Use relative time ranges (last 5m, last 1h)

### Superset Real-Time
- Enable auto-refresh on dashboards
- Set refresh intervals based on data freshness needs
- Use cached queries for better performance

## Key Performance Indicators (KPIs)

Monitor these metrics:

**System Health:**
- Flink job uptime
- TaskManager availability  
- Memory/CPU utilization
- Message lag

**Business Metrics:**
- Events per second
- Unique active users
- Page view trends
- Geographic reach
- User engagement patterns

## Troubleshooting

**If Grafana shows no data:**
1. Check Prometheus targets: http://localhost:9090/targets
2. Verify Flink metrics ports are accessible
3. Restart Prometheus: `docker-compose restart prometheus`

**If Superset can't connect to ClickHouse:**
1. Test connection manually: `docker exec -it clickhouse clickhouse-client`
2. Verify network connectivity between containers
3. Check ClickHouse logs: `docker logs clickhouse`

**Performance Optimization:**
- Use proper time indices in ClickHouse queries
- Cache frequently used Superset queries
- Optimize Grafana panel queries
- Set appropriate retention policies