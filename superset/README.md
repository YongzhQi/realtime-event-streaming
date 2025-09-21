# Superset Configuration for ClickHouse Integration

## Setup Instructions

### 1. Access Superset
After running `docker-compose up -d`, access Superset at http://localhost:8088

### 2. Create Admin User (First Time)
```bash
docker exec -it superset superset fab create-admin \
    --username admin \
    --firstname Admin \
    --lastname User \
    --email admin@example.com \
    --password admin
```

### 3. Initialize Superset Database
```bash
docker exec -it superset superset db upgrade
docker exec -it superset superset init
```

### 4. Add ClickHouse Database Connection

In Superset UI:
1. Go to Settings > Database Connections
2. Click "+ DATABASE"
3. Choose "ClickHouse" from the list
4. Enter connection details:
   - **Host**: `clickhouse`
   - **Port**: `8123`
   - **Database**: `rt`
   - **Username**: `default`
   - **Password**: (leave empty)

**SQLAlchemy URI**: `clickhouse+http://default:@clickhouse:8123/rt`

### 5. Test Connection
Click "Test Connection" to verify the setup.

### 6. Create Datasets

#### Page Minute Aggregations
- **Dataset Name**: `page_minute_agg`
- **Schema**: `rt`
- **Table**: `page_minute_agg`

#### Recent Activity View
- **Dataset Name**: `recent_activity`
- **Schema**: `rt`
- **Table**: `recent_activity`

### 7. Sample Dashboard Charts

#### Chart 1: Real-time Page Views
- **Chart Type**: Line Chart
- **Dataset**: `page_minute_agg`
- **X-Axis**: `window_start`
- **Y-Axis**: `cnt`
- **Group By**: `page`
- **Time Range**: Last 1 hour
- **Auto Refresh**: 30 seconds

#### Chart 2: Unique Users by Country
- **Chart Type**: Bar Chart
- **Dataset**: `page_minute_agg`
- **X-Axis**: `country`
- **Y-Axis**: `unique_users`
- **Time Range**: Last 1 hour

#### Chart 3: Top Pages
- **Chart Type**: Table
- **Dataset**: `recent_activity`
- **Columns**: `page`, `total_clicks`, `unique_users`
- **Sort By**: `total_clicks` DESC
- **Row Limit**: 10

### 8. Dashboard Configuration
- **Auto Refresh**: Set to 30 seconds
- **Time Range**: Last 1 hour (rolling)
- **Filters**: Add country and device filters

## ClickHouse Connection Troubleshooting

### Common Issues:

1. **Connection Timeout**
   - Ensure ClickHouse container is running: `docker ps`
   - Check network connectivity: `docker exec -it superset ping clickhouse`

2. **Authentication Failed**
   - ClickHouse default user has no password
   - Use: `clickhouse+http://default:@clickhouse:8123/rt`

3. **Database Not Found**
   - Ensure ClickHouse initialization script ran
   - Check database exists: `docker exec -it clickhouse clickhouse-client --query "SHOW DATABASES"`

### Alternative Connection Methods:

#### Using Native Protocol (Port 9000):
```
clickhouse+native://default:@clickhouse:9000/rt
```

#### Using HTTPS (if SSL enabled):
```
clickhouse+https://default:@clickhouse:8123/rt
```

## Sample SQL Queries for Testing

### Test Database Connection:
```sql
SELECT 1
```

### Check Recent Data:
```sql
SELECT 
    page,
    country,
    count() as clicks,
    uniqExact(user_id) as unique_users
FROM rt.clicks_raw 
WHERE ts >= now() - INTERVAL 1 HOUR
GROUP BY page, country
ORDER BY clicks DESC
LIMIT 10
```

### Window Aggregation Status:
```sql
SELECT 
    window_start,
    count() as windows,
    sum(cnt) as total_clicks,
    sum(unique_users) as total_unique_users
FROM rt.page_minute_agg 
WHERE window_start >= now() - INTERVAL 1 HOUR
GROUP BY window_start
ORDER BY window_start DESC
```