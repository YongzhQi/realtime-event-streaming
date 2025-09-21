#!/bin/bash

# Stop and cleanup script for the real-time streaming pipeline

set -e

echo "Stopping Real-Time Event Streaming Pipeline..."

# Stop the producer if running
echo "⏹Stopping producer..."
pkill -f "python.*produce.py" || true

# Stop Flink jobs
echo "⏹Stopping Flink jobs..."
cd docker
docker exec flink-jobmanager flink list -r | grep -E "^[a-f0-9]" | awk '{print $1}' | xargs -I {} docker exec flink-jobmanager flink cancel {} || true

# Stop all services
echo "Stopping Docker services..."
docker-compose down

# Optional: Remove volumes (uncomment if you want to clean all data)
# echo "Removing volumes..."
# docker-compose down -v

echo "Pipeline stopped successfully!"
echo ""
echo "To start again, run: ./start.sh"
echo "To remove all data, run: docker-compose down -v"