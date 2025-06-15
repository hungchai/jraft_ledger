#!/bin/bash

# ============================================================================
# JRaft Ledger System - Test Script for JRaft Profile
# This script demonstrates how to run the application with JRaft enabled
# ============================================================================

echo "🚀 JRaft Ledger System - Testing JRaft Profile"
echo "=============================================="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if Docker is running
print_status "Checking Docker status..."
if ! docker info > /dev/null 2>&1; then
    print_error "Docker is not running. Please start Docker first."
    exit 1
fi
print_success "Docker is running"

# Check if MySQL container is running
print_status "Checking MySQL container..."
if ! docker ps | grep -q "dev-mysql"; then
    print_warning "MySQL container not found. Starting MySQL..."
    # You may need to adjust this based on your docker-compose setup
    print_error "Please start your MySQL container first"
    exit 1
fi
print_success "MySQL container is running"

# Clean up any existing JRaft data
print_status "Cleaning up existing JRaft data..."
rm -rf ./raft-data-node1
rm -rf ./rocksdb-data-node1
rm -rf ./rocksdb-wal-node1
print_success "Cleaned up existing data"

# Compile the project
print_status "Compiling the project..."
if ! mvn clean compile -q; then
    print_error "Compilation failed"
    exit 1
fi
print_success "Compilation successful"

# Start the application with JRaft profile
print_status "Starting application with JRaft profile..."
print_warning "This will start the application on port 8091 with JRaft enabled"
print_warning "Press Ctrl+C to stop the application"

echo ""
echo "🔧 Configuration:"
echo "   - Profile: raft"
echo "   - Port: 8091"
echo "   - JRaft Group: ledger-raft-group"
echo "   - Node ID: node1"
echo "   - Data Path: ./raft-data-node1"
echo "   - RocksDB Path: ./rocksdb-data-node1"
echo ""

# Run with JRaft profile
mvn spring-boot:run -Dspring-boot.run.profiles=raft

print_status "Application stopped" 