#!/bin/bash
# Configure Superset with ClickHouse database connection

echo "Setting up Superset with ClickHouse connection..."

# Add ClickHouse database connection to Superset
docker exec -it superset superset fab create-db

# Create admin user (if not exists)
docker exec -it superset superset fab create-admin \
    --username admin \
    --firstname Admin \
    --lastname User \
    --email admin@superset.com \
    --password admin

# Upgrade Superset database
docker exec -it superset superset db upgrade

# Initialize Superset
docker exec -it superset superset init

echo "Superset setup complete!"
echo "Access Superset at: http://localhost:8088"
echo "Default login: admin/admin"
echo ""
echo "To add ClickHouse database:"
echo "1. Go to Settings -> Database Connections"
echo "2. Click + DATABASE"
echo "3. Select 'ClickHouse'"
echo "4. Database name: rt"
echo "5. Host: clickhouse"
echo "6. Port: 8123"
echo "7. Username: default"
echo "8. Password: (leave empty)"