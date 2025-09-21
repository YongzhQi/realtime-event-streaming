# Real-Time Event Streaming Pipeline

A comprehensive real-time analytics pipeline that processes web click events with sub-second query latency. This project demonstrates modern streaming architecture using Kafka, Apache Flink, and ClickHouse.

## Architecture Overview

```
Web Events → Kafka → Flink → ClickHouse
```

### Components

- **Apache Kafka**: Event streaming platform for ingesting click events
- **Apache Flink**: Stream processing engine for real-time aggregations
- **ClickHouse**: OLAP database for fast analytical queries
- **Python Producer**: Synthetic event generator

## Quick Start

### Prerequisites

- Docker and Docker Compose
- Python 3.8+ (for event producer)
- Java 17+ and Maven (for Flink job development)
- 8GB+ RAM recommended

### 1. Start the Infrastructure

```bash
cd docker
docker-compose up -d
```

This will start all services:
- Kafka: `localhost:9092`
- Flink Web UI: `http://localhost:8081`
- ClickHouse: `localhost:8123` (HTTP), `localhost:9000` (Native)

### 2. Wait for Services to be Ready

```bash
# Check service health
docker-compose ps

# Wait for ClickHouse to initialize tables
docker logs clickhouse
```

### 3. Build and Submit Flink Job

```bash
cd flink-job
mvn clean package -DskipTests

# Copy JAR to Flink container
docker cp target/flink-streaming-job-1.0-SNAPSHOT.jar flink-jobmanager:/job.jar

# Submit the job
docker exec flink-jobmanager flink run /job.jar
```

### 4. Start Event Producer

```bash
cd producer
pip install -r requirements.txt
python produce.py --rate 100
```

### 5. Access Web UIs

#### Flink Web UI (Job Monitoring)
1. Go to `http://localhost:8081`
2. Monitor job status, throughput, and resource usage

#### ClickHouse (Query Interface)
1. Access via HTTP: `http://localhost:8123`
2. Or connect with ClickHouse client on port `9000`

## Data Flow

### Event Schema
```json
{
  "event_id": "uuid",
  "user_id": "u123456",
  "ts": 1715200123456,
  "page": "/product/42",
  "referrer": "/home",
  "country": "US",
  "device": "mobile"
}
```

### Processing Pipeline

1. **Ingestion**: Events sent to Kafka topic `web.clicks`
2. **Stream Processing**: Flink job performs:
   - Event-time processing with watermarks
   - 1-minute tumbling windows
   - Aggregations by page + country
   - Anomaly detection (optional)
3. **Storage**: Results written to ClickHouse tables:
   - `rt.clicks_raw`: Individual events
   - `rt.page_minute_agg`: Windowed aggregations
4. **Analysis**: Query data directly in ClickHouse for real-time insights

## Development

### Project Structure

```
realtime-event-streaming/
├── docker/                    # Docker infrastructure
│   └── docker-compose.yml
├── flink-job/                 # Flink streaming application
│   ├── pom.xml
│   └── src/main/java/com/example/
├── producer/                  # Event generator
│   ├── produce.py
│   └── requirements.txt
├── sql/                       # ClickHouse schema
│   └── clickhouse_tables.sql
└── docs/                      # Documentation
    └── README.md
```

### Key Features

#### Flink Job Capabilities
- **Fault Tolerance**: Checkpointing every 30 seconds
- **Event Time Processing**: Handles out-of-order events
- **Watermark Strategy**: 10-second bounded out-of-orderness
- **Anomaly Detection**: Z-score based outlier detection
- **Backpressure Handling**: Configurable parallelism

#### ClickHouse Optimizations
- **Partitioning**: By month for efficient queries
- **Indexing**: Bloom filters for fast lookups
- **TTL**: Automatic data expiration
- **Materialized Views**: Real-time aggregations

#### Monitoring & Observability
- **Flink Web UI**: Real-time job monitoring
- **ClickHouse Queries**: Performance metrics via SQL
- **Docker Health Checks**: Container health monitoring

## Performance Tuning

### Kafka Configuration
```bash
# Increase throughput
KAFKA_CFG_NUM_NETWORK_THREADS=8
KAFKA_CFG_NUM_IO_THREADS=16
KAFKA_CFG_SOCKET_SEND_BUFFER_BYTES=102400
KAFKA_CFG_SOCKET_RECEIVE_BUFFER_BYTES=102400
```

### Flink Tuning
```bash
# Memory configuration
taskmanager.memory.process.size: 2048m
jobmanager.memory.process.size: 1024m

# Parallelism
parallelism.default: 4
taskmanager.numberOfTaskSlots: 4
```

### ClickHouse Optimization
```sql
-- Increase buffer sizes
SET max_memory_usage = 20000000000;
SET max_bytes_before_external_group_by = 2000000000;
```

## Operations

### Scaling

#### Horizontal Scaling
```bash
# Scale Kafka partitions
docker exec kafka kafka-topics.sh --bootstrap-server kafka:9092 \
  --alter --topic web.clicks --partitions 12

# Scale Flink task managers
docker-compose up -d --scale flink-taskmanager=3
```

#### Vertical Scaling
Adjust memory limits in `docker-compose.yml`:
```yaml
services:
  flink-taskmanager:
    environment:
      - FLINK_PROPERTIES=taskmanager.memory.process.size: 4096m
```

### Monitoring

#### Key Metrics to Watch
- **Kafka**: Topic throughput, message counts
- **Flink**: Records/sec, task status, checkpoints
- **ClickHouse**: Query duration, row counts, disk usage

#### Available Metrics
Current pipeline handles ~98 events with 4.7s avg / 9s P95 latency

### Backup & Recovery

#### ClickHouse Backup
```bash
# Create backup
docker exec clickhouse clickhouse-backup create

# Restore from backup
docker exec clickhouse clickhouse-backup restore <backup_name>
```

#### Flink State Backup
```bash
# Savepoint creation
docker exec flink-jobmanager flink savepoint <job_id> s3://backup-bucket/savepoints/
```

## Testing

### Unit Tests
```bash
cd flink-job
mvn test
```

### Integration Tests
```bash
# Start test environment
docker-compose -f docker-compose.test.yml up -d

# Run end-to-end tests
./scripts/e2e-test.sh
```

### Load Testing
```bash
# High-volume producer
python producer/produce.py --rate 10000 --duration 300
```

## Troubleshooting

### Common Issues

#### Flink Job Won't Start
```bash
# Check logs
docker logs flink-jobmanager
docker logs flink-taskmanager

# Check job status
docker exec flink-jobmanager flink list
```

#### ClickHouse Connection Issues
```bash
# Test connectivity
docker exec flink-jobmanager wget -qO- http://clickhouse:8123/ping

# Check ClickHouse logs
docker logs clickhouse
```

#### Kafka Consumer Lag
```bash
# Check consumer groups
docker exec kafka kafka-consumer-groups.sh --bootstrap-server kafka:9092 --list

# Check lag
docker exec kafka kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --group flink-click-processor
```

### Performance Issues

#### High Latency
1. Check watermark lag in Flink UI
2. Verify ClickHouse query performance
3. Monitor network between containers

#### Low Throughput
1. Increase Kafka partitions
2. Scale Flink parallelism
3. Tune ClickHouse batch settings

## Additional Resources

- [Apache Flink Documentation](https://flink.apache.org/docs/)
- [ClickHouse Documentation](https://clickhouse.com/docs/)
- [Kafka Documentation](https://kafka.apache.org/documentation/)