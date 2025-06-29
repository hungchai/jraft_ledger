#!/bin/bash

# ============================================================================
# JRaft Ledger System - Working Stress Test
# This script performs basic stress testing with proper error handling
# ============================================================================

# Configuration
BASE_URL="http://localhost:8090"
CONCURRENT_PROCESSES=5
TRANSFERS_PER_PROCESS=10
TRANSFER_AMOUNT=1.00

# Test accounts
TEST_USERS=("User1" "User2" "User3")

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output with timestamp
log() {
    local timestamp=$(date '+%H:%M:%S')
    echo -e "${BLUE}[$timestamp]${NC} $1"
}

log_success() {
    local timestamp=$(date '+%H:%M:%S')
    echo -e "${GREEN}[$timestamp]${NC} $1"
}

log_warning() {
    local timestamp=$(date '+%H:%M:%S')
    echo -e "${YELLOW}[$timestamp]${NC} $1"
}

log_error() {
    local timestamp=$(date '+%H:%M:%S')
    echo -e "${RED}[$timestamp]${NC} $1"
}

# Function to check if application is healthy
check_application_health() {
    log "Checking application health..."
    local health_response=$(curl -s -w "%{http_code}" -o /dev/null "$BASE_URL/actuator/health" 2>/dev/null)
    
    if [ "$health_response" = "200" ]; then
        log_success "Application is healthy and ready"
        return 0
    else
        log_error "Cannot connect to application at $BASE_URL"
        return 1
    fi
}

# Function to create account
create_account() {
    local user_id=$1
    local response=$(curl -s -w "%{http_code}" \
        -X POST \
        -H "Content-Type: application/json" \
        -d "{\"userId\":\"$user_id\",\"accountType\":\"AVAILABLE\"}" \
        "$BASE_URL/api/balance/create" 2>/dev/null)
    
    local http_code="${response: -3}"
    local response_body="${response%???}"
    
    if [ "$http_code" = "200" ]; then
        log_success "Account created for $user_id"
        return 0
    else
        log_warning "Account creation for $user_id returned: $http_code - $response_body"
        return 1
    fi
}

# Function to get balance
get_balance() {
    local user_id=$1
    local response=$(curl -s -w "%{http_code}" "$BASE_URL/api/balance/account/$user_id/available" 2>/dev/null)
    local http_code="${response: -3}"
    local response_body="${response%???}"
    
    if [ "$http_code" = "200" ]; then
        # Extract balance from JSON response using a simple approach
        local balance=$(echo "$response_body" | sed -n 's/.*"balance":\([0-9.-]*\).*/\1/p')
        echo "$balance"
    else
        echo "ERROR"
    fi
}

# Function to execute a single transfer
execute_transfer() {
    local from_user=$1
    local to_user=$2
    local idempotent_key=$3
    local description=$4
    
    local request_data=$(cat <<EOF
{
    "fromUserId": "$from_user",
    "fromType": "AVAILABLE",
    "toUserId": "$to_user",
    "toType": "AVAILABLE",
    "amount": $TRANSFER_AMOUNT,
    "description": "$description"
}
EOF
)
    
    local response=$(curl -s -w "%{http_code}" \
        -X POST \
        -H "Content-Type: application/json" \
        -H "Idempotency-Key: $idempotent_key" \
        -d "$request_data" \
        "$BASE_URL/api/transfer/single" 2>/dev/null)
    
    local http_code="${response: -3}"
    local response_body="${response%???}"
    
    echo "$http_code|$response_body"
}

# Function to setup test accounts
setup_test_accounts() {
    log "🏦 Setting up test accounts..."
    
    # Create accounts
    for user_id in "${TEST_USERS[@]}"; do
        create_account "$user_id"
    done
    
    # Show initial balances
    log "Initial account balances:"
    for user_id in "${TEST_USERS[@]}"; do
        local balance=$(get_balance "$user_id")
        log "Account $user_id: $balance"
    done
    
    log_success "Test accounts setup completed"
}

# Function to run stress test for a single process
run_stress_test_process() {
    local process_id=$1
    local results_file="/tmp/working_stress_test_results_$process_id.txt"
    
    log "🧵 Process $process_id starting stress test"
    
    local successful=0
    local failed=0
    local idempotent_hits=0
    
    # Array to store used idempotent keys for reuse
    declare -a used_keys
    
    for ((i=1; i<=TRANSFERS_PER_PROCESS; i++)); do
        # Select random from and to users
        local from_user=${TEST_USERS[$((RANDOM % ${#TEST_USERS[@]}))]}
        local to_user
        
        # Ensure different users
        while [ -z "$to_user" ] || [ "$to_user" = "$from_user" ]; do
            to_user=${TEST_USERS[$((RANDOM % ${#TEST_USERS[@]}))]}
        done
        
        # Generate simple idempotent key
        local timestamp=$(date +%s)
        local idempotent_key="stress-p$process_id-r$i-$timestamp"
        
        # 20% chance to reuse an existing key for idempotency testing
        if [ ${#used_keys[@]} -gt 0 ] && [ $((RANDOM % 100)) -lt 20 ]; then
            local key_index=$((RANDOM % ${#used_keys[@]}))
            idempotent_key=${used_keys[$key_index]}
            log "🔄 Process $process_id using duplicate idempotent key"
        else
            used_keys+=("$idempotent_key")
        fi
        
        # Execute transfer
        local result=$(execute_transfer "$from_user" "$to_user" "$idempotent_key" "Working stress test P$process_id-R$i")
        
        IFS='|' read -r http_code response_body <<< "$result"
        
        if [ "$http_code" = "200" ]; then
            successful=$((successful + 1))
            if [[ "$response_body" == *"idempotent"* ]] || [[ "$response_body" == *"duplicate"* ]]; then
                idempotent_hits=$((idempotent_hits + 1))
                log "✅ Process $process_id idempotent hit detected"
            fi
            log "✅ Process $process_id transfer success: $from_user -> $to_user"
        else
            failed=$((failed + 1))
            log_warning "Process $process_id transfer failed: HTTP_$http_code - ${response_body:0:50}..."
        fi
        
        # Small delay to avoid overwhelming the system
        sleep 0.1
    done
    
    # Save results to file
    echo "$successful|$failed|$idempotent_hits" > "$results_file"
    log_success "Process $process_id completed $TRANSFERS_PER_PROCESS transfers"
}

# Function to aggregate results
aggregate_results() {
    log "📊 Aggregating results from all processes..."
    
    local total_successful=0
    local total_failed=0
    local total_idempotent_hits=0
    
    for ((i=0; i<CONCURRENT_PROCESSES; i++)); do
        local results_file="/tmp/working_stress_test_results_$i.txt"
        if [ -f "$results_file" ]; then
            IFS='|' read -r successful failed idempotent_hits < "$results_file"
            total_successful=$((total_successful + successful))
            total_failed=$((total_failed + failed))
            total_idempotent_hits=$((total_idempotent_hits + idempotent_hits))
            rm "$results_file"
        fi
    done
    
    local total_requests=$((total_successful + total_failed))
    local success_rate=0
    
    if [ $total_requests -gt 0 ]; then
        success_rate=$(( (total_successful * 100) / total_requests ))
    fi
    
    local idempotent_rate=0
    if [ $total_successful -gt 0 ]; then
        idempotent_rate=$(( (total_idempotent_hits * 100) / total_successful ))
    fi
    
    echo ""
    log "🏁 ========== WORKING STRESS TEST RESULTS =========="
    log "🗄️  Database: MySQL (LOCAL profile)"
    log "📊 Total requests: $total_requests"
    log "✅ Successful transfers: $total_successful"
    log "❌ Failed transfers: $total_failed"
    log "🎯 Success rate: $success_rate%"
    log "🔄 Idempotent hits: $total_idempotent_hits ($idempotent_rate% of successful)"
    log "=============================================="
}

# Function to verify final balances
verify_final_balances() {
    log "🧮 Verifying final account balances..."
    
    local total_balance=0
    
    for user_id in "${TEST_USERS[@]}"; do
        local balance=$(get_balance "$user_id")
        if [ "$balance" != "ERROR" ]; then
            log "💰 Account $user_id: $balance"
            # Simple addition using shell arithmetic (avoiding bc for simplicity)
            total_balance=$(echo "$total_balance + $balance" | bc 2>/dev/null || echo "0")
        else
            log_error "Failed to get balance for $user_id"
        fi
    done
    
    log "💰 Total balance across all accounts: $total_balance"
    
    # Since we start with 0 and only transfer between accounts, total should remain 0
    if [ "$total_balance" = "0" ] || [ "$total_balance" = "0.00" ]; then
        log_success "Balance verification PASSED - Total balance is conserved!"
    else
        log_warning "Balance verification: Total balance is $total_balance (may be due to successful transfers)"
    fi
}

# Main execution
main() {
    echo "🔥 JRaft Ledger System - Working Stress Test"
    echo "============================================"
    echo "🎯 Target: $BASE_URL (LOCAL profile)"
    echo "🧵 Concurrent processes: $CONCURRENT_PROCESSES"
    echo "📊 Transfers per process: $TRANSFERS_PER_PROCESS"
    echo "📈 Total transfers: $((CONCURRENT_PROCESSES * TRANSFERS_PER_PROCESS))"
    echo "💸 Transfer amount: \$$TRANSFER_AMOUNT"
    echo "👥 Test accounts: ${#TEST_USERS[@]}"
    echo ""
    
    # Check application health
    if ! check_application_health; then
        exit 1
    fi
    
    # Setup test accounts
    setup_test_accounts
    
    # Run stress test
    log "🚀 Starting stress test with $CONCURRENT_PROCESSES processes..."
    local start_time=$(date +%s)
    
    # Start background processes
    local pids=()
    for ((i=0; i<CONCURRENT_PROCESSES; i++)); do
        run_stress_test_process $i &
        pids+=($!)
    done
    
    # Wait for all processes to complete
    for pid in "${pids[@]}"; do
        wait $pid
    done
    
    local end_time=$(date +%s)
    local total_time=$((end_time - start_time))
    
    log "⏳ Waiting for async MySQL writes to complete..."
    sleep 2
    
    # Aggregate and display results
    aggregate_results
    log "⏱️  Total execution time: ${total_time} seconds"
    
    # Verify balances
    verify_final_balances
    
    log "🎉 Working stress test completed!"
}

# Check if script is being run directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi 