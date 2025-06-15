#!/bin/bash

# ============================================================================
# MySQL Batch Writer Performance Test Script
# This script tests the performance of AsyncMySQLBatchWriter with different configs
# ============================================================================

echo "🚀 MySQL Batch Writer Performance Test"
echo "====================================="

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
    print_error "Please start your MySQL container first"
    exit 1
fi
print_success "MySQL container is running"

# Function to run a test with specific configuration
run_test() {
    local test_name=$1
    local use_ring_buffer=$2
    local thread_count=$3
    local batch_size=$4
    local interval_ms=$5
    local ring_buffer_size=$6
    
    echo ""
    echo "🧪 Running Test: $test_name"
    echo "   - Ring Buffer: $use_ring_buffer"
    echo "   - Thread Count: $thread_count"
    echo "   - Batch Size: $batch_size"
    echo "   - Interval (ms): $interval_ms"
    echo "   - Ring Buffer Size: $ring_buffer_size"
    
    # Start application with specific configuration
    print_status "Starting application..."
    
    # Run with specific configuration
    nohup mvn spring-boot:run \
        -Dspring-boot.run.jvmArguments="-Xmx512m" \
        -Dspring-boot.run.profiles=local \
        -Dmysql.use.ring.buffer=$use_ring_buffer \
        -Dmysql.writer.thread.count=$thread_count \
        -Dmysql.batch.size=$batch_size \
        -Dmysql.batch.interval.ms=$interval_ms \
        -Dmysql.ring.buffer.size=$ring_buffer_size > test-output.log 2>&1 &
    
    APP_PID=$!
    print_status "Application started with PID: $APP_PID"
    
    # Wait for application to start
    print_status "Waiting for application to start..."
    sleep 15
    
    # Check if application is running
    if ! ps -p $APP_PID > /dev/null; then
        print_error "Application failed to start. Check test-output.log for details."
        exit 1
    fi
    
    # Get initial metrics
    print_status "Getting initial metrics..."
    curl -s http://localhost:8090/api/admin/metrics/mysql-writer > initial_metrics.json
    
    # Run load test
    print_status "Running load test..."
    
    # Perform 1000 transfers (adjust as needed)
    for i in {1..1000}; do
        curl -s -X POST http://localhost:8090/api/transfer/single \
            -H 'Content-Type: application/json' \
            -d "{\"fromUserId\":\"Bank\",\"fromType\":\"AVAILABLE\",\"toUserId\":\"UserA\",\"toType\":\"AVAILABLE\",\"amount\":0.01,\"description\":\"Test $i\"}" > /dev/null &
        
        # Add some randomness to simulate real-world load
        if [ $((i % 10)) -eq 0 ]; then
            sleep 0.01
        fi
    done
    
    # Wait for all transfers to complete
    print_status "Waiting for transfers to complete..."
    wait
    
    # Wait for batch processing
    print_status "Waiting for batch processing to complete..."
    sleep 5
    
    # Get final metrics
    print_status "Getting final metrics..."
    curl -s http://localhost:8090/api/admin/metrics/mysql-writer > final_metrics.json
    
    # Stop application
    print_status "Stopping application..."
    kill $APP_PID
    sleep 2
    
    # Print results
    print_success "Test completed: $test_name"
    echo "Results:"
    echo "  Initial metrics: $(cat initial_metrics.json)"
    echo "  Final metrics: $(cat final_metrics.json)"
    echo ""
}

# Clean up any existing metrics files
rm -f initial_metrics.json final_metrics.json test-output.log

# Run tests with different configurations

# Test 1: Traditional Queue with Single Thread
run_test "Traditional Queue (Single Thread)" "false" "1" "200" "100" "16384"

# Test 2: Traditional Queue with Multiple Threads
run_test "Traditional Queue (Multiple Threads)" "false" "4" "200" "100" "16384"

# Test 3: RingBuffer with Default Settings
run_test "RingBuffer (Default)" "true" "4" "200" "100" "16384"

# Test 4: RingBuffer with Larger Size
run_test "RingBuffer (Large)" "true" "4" "200" "100" "65536"

# Test 5: RingBuffer with Small Batches
run_test "RingBuffer (Small Batches)" "true" "4" "50" "20" "16384"

print_success "All performance tests completed!"
echo "Compare the metrics to determine the optimal configuration for your workload." 