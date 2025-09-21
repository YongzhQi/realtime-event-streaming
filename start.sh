#!/bin/bash

# Quick start script for the real-time streaming pipeline
# This script automates the entire setup process

set -e

echo "Starting Real-Time Event Streaming Pipeline Setup..."

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Check prerequisites
echo "📋 Checking prerequisites..."
if ! command_exists docker; then
    echo "Docker is not installed. Please install Docker first."
    exit 1
fi

if ! command_exists docker-compose; then
    echo "Docker Compose is not installed. Please install Docker Compose first."
    exit 1
fi

if ! command_exists python3; then
    echo "Python 3 is not installed. Please install Python 3 first."
    exit 1
fi

if ! command_exists mvn; then
    echo "Maven is not installed. Please install Maven first."
    exit 1
fi

echo "All prerequisites are installed."

# Start infrastructure
echo "Starting infrastructure services..."
cd docker
docker-compose up -d

echo "Waiting for services to be ready..."
sleep 30

# Check if services are healthy
echo "Checking service health..."
docker-compose ps

# Wait for ClickHouse to initialize
echo "Waiting for ClickHouse to initialize tables..."
sleep 20

# Build and submit Flink job
echo "Building Flink job..."
cd ../flink-job
mvn clean package -DskipTests

echo " Deploying Flink job..."
docker cp target/flink-streaming-job-1.0-SNAPSHOT.jar flink-jobmanager:/job.jar
docker exec flink-jobmanager flink run /job.jar

# Install Python dependencies for producer
echo " Setting up Python environment..."
cd ../producer
pip3 install -r requirements.txt

echo " Setup complete!"
echo ""
echo "Real-Time Event Streaming Pipeline is now running!"
echo ""
echo "Access Points:"
echo "  • Flink Web UI:       http://localhost:8081"
echo "  • ClickHouse:         http://localhost:8123"
echo "  • Superset:          http://localhost:8088 (admin/admin)"
echo "  • Grafana:           http://localhost:3000 (admin/admin)"
echo "  • Prometheus:        http://localhost:9090"
echo ""
echo "To start generating events:"
echo "  cd producer && python3 produce.py --rate 100"
echo ""
echo "For detailed instructions, see README.md"