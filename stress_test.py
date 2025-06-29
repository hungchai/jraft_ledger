#!/usr/bin/env python3
"""
JRaft Ledger System - Stress Test Script
This script performs concurrent transfer stress testing with idempotency
against the LOCAL profile (MySQL database).
"""

import requests
import json
import time
import threading
import random
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import defaultdict, Counter
import uuid
import sys

# Configuration
BASE_URL = "http://localhost:8090"  # Local profile port
CONCURRENT_THREADS = 15
TRANSFERS_PER_THREAD = 30
IDEMPOTENT_DUPLICATE_PERCENTAGE = 25  # 25% of requests will be duplicated
INITIAL_BALANCE = 10000.00
TRANSFER_AMOUNT = 10.00

# Test accounts
TEST_USERS = [
    "StressUser1", "StressUser2", "StressUser3", "StressUser4", "StressUser5",
    "StressUser6", "StressUser7", "StressUser8"
]

# Global metrics
metrics = {
    'successful_transfers': 0,
    'failed_transfers': 0,
    'idempotent_hits': 0,
    'total_response_time': 0,
    'error_counts': defaultdict(int),
    'response_times': []
}

metrics_lock = threading.Lock()

def log(message):
    """Thread-safe logging with timestamp"""
    timestamp = time.strftime("%H:%M:%S")
    print(f"[{timestamp}] {message}")

def setup_test_accounts():
    """Initialize test accounts with initial balances"""
    log("🏦 Setting up test accounts with initial balances...")
    
    for user_id in TEST_USERS:
        request_data = {
            "fromUserId": "Bank",
            "fromType": "AVAILABLE",
            "toUserId": user_id,
            "toType": "AVAILABLE",
            "amount": INITIAL_BALANCE,
            "description": f"Initial funding for stress test - {user_id}"
        }
        
        try:
            response = requests.post(
                f"{BASE_URL}/api/transfer/single",
                json=request_data,
                headers={"Content-Type": "application/json"},
                timeout=10
            )
            
            if response.status_code == 200:
                log(f"✅ Funded account: {user_id} with ${INITIAL_BALANCE}")
            else:
                log(f"❌ Failed to fund account: {user_id}, status: {response.status_code}, response: {response.text}")
        except Exception as e:
            log(f"❌ Exception funding account {user_id}: {e}")
    
    # Wait for async MySQL writes to complete
    log("⏳ Waiting for async MySQL writes to complete...")
    time.sleep(3)
    log("✅ Test accounts setup completed")

def execute_transfer_with_idempotency(request_data, idempotent_key):
    """Execute a single transfer with idempotency key"""
    headers = {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotent_key
    }
    
    start_time = time.time()
    try:
        response = requests.post(
            f"{BASE_URL}/api/transfer/single",
            json=request_data,
            headers=headers,
            timeout=15
        )
        end_time = time.time()
        response_time_ms = (end_time - start_time) * 1000
        
        return {
            'status_code': response.status_code,
            'response_text': response.text,
            'response_time_ms': response_time_ms,
            'success': response.status_code == 200
        }
    except Exception as e:
        end_time = time.time()
        response_time_ms = (end_time - start_time) * 1000
        return {
            'status_code': 0,
            'response_text': str(e),
            'response_time_ms': response_time_ms,
            'success': False,
            'exception': True
        }

def run_transfer_stress_test(thread_id):
    """Run stress test for a single thread"""
    log(f"🧵 Thread {thread_id} starting stress test")
    
    used_idempotent_keys = set()
    thread_metrics = {
        'successful': 0,
        'failed': 0,
        'idempotent_hits': 0,
        'response_times': []
    }
    
    for i in range(TRANSFERS_PER_THREAD):
        try:
            # Select random from and to users
            from_user = random.choice(TEST_USERS)
            to_user = random.choice([u for u in TEST_USERS if u != from_user])
            
            # Create transfer request
            request_data = {
                "fromUserId": from_user,
                "fromType": "AVAILABLE",
                "toUserId": to_user,
                "toType": "AVAILABLE",
                "amount": TRANSFER_AMOUNT,
                "description": f"Stress test transfer T{thread_id}-R{i}"
            }
            
            # Generate idempotent key
            idempotent_key = f"stress-test-t{thread_id}-r{i}-{int(time.time() * 1000)}"
            
            # Decide if this should be a duplicate request for idempotency testing
            is_duplicate = random.randint(1, 100) <= IDEMPOTENT_DUPLICATE_PERCENTAGE
            if is_duplicate and used_idempotent_keys:
                # Reuse a previous idempotent key
                idempotent_key = random.choice(list(used_idempotent_keys))
                log(f"🔄 Thread {thread_id} using duplicate idempotent key: {idempotent_key}")
            else:
                used_idempotent_keys.add(idempotent_key)
            
            # Execute transfer
            result = execute_transfer_with_idempotency(request_data, idempotent_key)
            thread_metrics['response_times'].append(result['response_time_ms'])
            
            # Process result
            if result['success']:
                thread_metrics['successful'] += 1
                if is_duplicate and ('idempotent' in result['response_text'].lower() or 
                                   'duplicate' in result['response_text'].lower()):
                    thread_metrics['idempotent_hits'] += 1
                    log(f"✅ Thread {thread_id} idempotent hit detected")
                log(f"✅ Thread {thread_id} transfer success: {from_user} -> {to_user} ({result['response_time_ms']:.1f}ms)")
            else:
                thread_metrics['failed'] += 1
                error_key = f"HTTP_{result['status_code']}" if not result.get('exception') else "EXCEPTION"
                with metrics_lock:
                    metrics['error_counts'][error_key] += 1
                log(f"⚠️ Thread {thread_id} transfer failed: {error_key} - {result['response_text'][:100]}")
            
            # Random delay to simulate realistic load
            if random.randint(1, 10) <= 4:  # 40% chance
                time.sleep(random.uniform(0.05, 0.15))  # 50-150ms delay
                
        except Exception as e:
            thread_metrics['failed'] += 1
            with metrics_lock:
                metrics['error_counts']['EXCEPTION'] += 1
            log(f"❌ Thread {thread_id} exception during transfer {i}: {e}")
    
    # Update global metrics
    with metrics_lock:
        metrics['successful_transfers'] += thread_metrics['successful']
        metrics['failed_transfers'] += thread_metrics['failed']
        metrics['idempotent_hits'] += thread_metrics['idempotent_hits']
        metrics['response_times'].extend(thread_metrics['response_times'])
        metrics['total_response_time'] += sum(thread_metrics['response_times'])
    
    log(f"✅ Thread {thread_id} completed {TRANSFERS_PER_THREAD} transfers")
    return thread_metrics

def print_stress_test_results(total_time_seconds):
    """Print comprehensive stress test results"""
    total_requests = metrics['successful_transfers'] + metrics['failed_transfers']
    success_rate = (metrics['successful_transfers'] / total_requests * 100) if total_requests > 0 else 0
    avg_response_time = (metrics['total_response_time'] / total_requests) if total_requests > 0 else 0
    throughput = total_requests / total_time_seconds if total_time_seconds > 0 else 0
    
    # Calculate percentiles
    response_times = sorted(metrics['response_times'])
    p50 = response_times[len(response_times)//2] if response_times else 0
    p95 = response_times[int(len(response_times)*0.95)] if response_times else 0
    p99 = response_times[int(len(response_times)*0.99)] if response_times else 0
    
    log("🏁 ========== MYSQL STRESS TEST RESULTS ==========")
    log("🗄️  Database: MySQL (LOCAL profile)")
    log(f"⏱️  Total execution time: {total_time_seconds:.2f} seconds")
    log(f"📊 Total requests: {total_requests}")
    log(f"✅ Successful transfers: {metrics['successful_transfers']}")
    log(f"❌ Failed transfers: {metrics['failed_transfers']}")
    log(f"🎯 Success rate: {success_rate:.2f}%")
    log(f"🔄 Idempotent hits: {metrics['idempotent_hits']} ({(metrics['idempotent_hits']/metrics['successful_transfers']*100) if metrics['successful_transfers'] > 0 else 0:.2f}% of successful)")
    log(f"⚡ Average response time: {avg_response_time:.2f} ms")
    log(f"📈 Response time P50: {p50:.2f} ms")
    log(f"📈 Response time P95: {p95:.2f} ms")
    log(f"📈 Response time P99: {p99:.2f} ms")
    log(f"🚀 Throughput: {throughput:.2f} requests/second")
    
    if metrics['error_counts']:
        log("🔍 Error breakdown:")
        for error, count in metrics['error_counts'].items():
            emoji = "❌" if error == "EXCEPTION" else "⚠️"
            log(f"   {emoji} {error}: {count} occurrences")
    
    log("===============================================")

def verify_final_balances():
    """Verify that total balance is conserved"""
    log("🧮 Verifying final account balances...")
    
    total_expected_balance = INITIAL_BALANCE * len(TEST_USERS)
    total_actual_balance = 0
    
    for user_id in TEST_USERS:
        try:
            response = requests.get(f"{BASE_URL}/api/balance/{user_id}/AVAILABLE", timeout=10)
            if response.status_code == 200:
                balance = float(response.text)
                total_actual_balance += balance
                log(f"💰 Account {user_id}: ${balance:.2f}")
            else:
                log(f"❌ Failed to get balance for {user_id}: {response.status_code}")
        except Exception as e:
            log(f"❌ Exception getting balance for {user_id}: {e}")
    
    log(f"💰 Total expected balance: ${total_expected_balance:.2f}")
    log(f"💰 Total actual balance: ${total_actual_balance:.2f}")
    
    if abs(total_expected_balance - total_actual_balance) < 0.01:  # Allow for floating point precision
        log("✅ Balance verification PASSED - No money lost or created!")
    else:
        log("❌ Balance verification FAILED - Money discrepancy detected!")
        log(f"💸 Difference: ${total_expected_balance - total_actual_balance:.2f}")

def check_application_health():
    """Check if the application is running and healthy"""
    try:
        response = requests.get(f"{BASE_URL}/actuator/health", timeout=5)
        if response.status_code == 200:
            log("✅ Application is healthy and ready")
            return True
        else:
            log(f"⚠️ Application health check failed: {response.status_code}")
            return False
    except Exception as e:
        log(f"❌ Cannot connect to application: {e}")
        log(f"❌ Make sure the application is running on {BASE_URL}")
        return False

def main():
    """Main stress test execution"""
    log("🔥 JRaft Ledger System - MySQL Stress Test")
    log("==========================================")
    log(f"🎯 Target: {BASE_URL} (LOCAL profile)")
    log(f"🧵 Concurrent threads: {CONCURRENT_THREADS}")
    log(f"📊 Transfers per thread: {TRANSFERS_PER_THREAD}")
    log(f"📈 Total transfers: {CONCURRENT_THREADS * TRANSFERS_PER_THREAD}")
    log(f"🔄 Idempotent duplicate percentage: {IDEMPOTENT_DUPLICATE_PERCENTAGE}%")
    log(f"💰 Initial balance per account: ${INITIAL_BALANCE}")
    log(f"💸 Transfer amount: ${TRANSFER_AMOUNT}")
    log(f"👥 Test accounts: {len(TEST_USERS)}")
    log("")
    
    # Check application health
    if not check_application_health():
        sys.exit(1)
    
    # Setup test accounts
    setup_test_accounts()
    
    # Run stress test
    log(f"🚀 Starting stress test with {CONCURRENT_THREADS} threads...")
    start_time = time.time()
    
    with ThreadPoolExecutor(max_workers=CONCURRENT_THREADS) as executor:
        # Submit all threads
        futures = [executor.submit(run_transfer_stress_test, i) for i in range(CONCURRENT_THREADS)]
        
        # Wait for completion
        for future in as_completed(futures):
            try:
                future.result()
            except Exception as e:
                log(f"❌ Thread execution failed: {e}")
    
    end_time = time.time()
    total_time = end_time - start_time
    
    # Wait for async MySQL writes to complete
    log("⏳ Waiting for async MySQL writes to complete...")
    time.sleep(3)
    
    # Print results
    print_stress_test_results(total_time)
    
    # Verify balances
    verify_final_balances()
    
    log("🎉 Stress test completed!")

if __name__ == "__main__":
    main() 