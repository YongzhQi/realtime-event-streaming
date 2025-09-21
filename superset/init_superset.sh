#!/bin/bash

# Superset initialization script
# This script sets up the admin user and configures ClickHouse connection

echo "Initializing Superset..."

# Wait for Superset to be ready
sleep 30

# Create admin user
superset fab create-admin \
    --username admin \
    --firstname Admin \
    --lastname User \
    --email admin@example.com \
    --password admin

# Initialize the database
superset db upgrade

# Load examples (optional)
# superset load_examples

# Initialize Superset
superset init

echo "Superset initialization complete!"
echo "Access Superset at http://localhost:8088"
echo "Username: admin"
echo "Password: admin"