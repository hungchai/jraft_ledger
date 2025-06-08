#!/bin/bash

# JRaft 分散式帳本系統集群部署腳本

set -e

echo "🚀 開始部署 JRaft 分散式帳本系統集群..."

# 清理舊的容器和映像
echo "📋 清理舊的部署..."
docker-compose down -v 2>/dev/null || true
docker system prune -f

# 構建並啟動集群
echo "🔨 構建並啟動集群..."
docker-compose up --build -d

echo "⏳ 等待服務啟動..."
sleep 30

# 檢查服務狀態
echo "🔍 檢查服務狀態..."
for i in {1..3}; do
    port=$((8078 + i * 2))
    echo "檢查節點 $i (端口 $port)..."
    
    # 等待服務完全啟動
    timeout=60
    counter=0
    while [ $counter -lt $timeout ]; do
        if curl -s -f "http://localhost:$port/api/ledger/status" > /dev/null 2>&1; then
            echo "✅ 節點 $i 已就緒"
            break
        fi
        echo "  等待節點 $i 啟動... ($counter/$timeout)"
        sleep 2
        counter=$((counter + 2))
    done
    
    if [ $counter -ge $timeout ]; then
        echo "❌ 節點 $i 啟動超時"
    fi
done

echo "🎉 集群部署完成！"
echo ""
echo "📊 集群信息："
echo "  - 節點 1: http://localhost:8080 (主要入口)"
echo "  - 節點 2: http://localhost:8082"
echo "  - 節點 3: http://localhost:8084"
echo "  - 負載均衡器: http://localhost (Nginx 代理)"
echo ""
echo "🔧 管理命令："
echo "  - 查看日誌: docker-compose logs -f [service_name]"
echo "  - 停止集群: docker-compose down"
echo "  - 清理數據: docker-compose down -v"
echo ""

# 創建測試帳戶
echo "💼 創建測試帳戶..."
create_account() {
    local account_number=$1
    local account_name=$2
    local initial_balance=$3
    
    curl -X POST "http://localhost:8080/api/ledger/accounts" \
        -H "Content-Type: application/json" \
        -d "{
            \"accountNumber\": \"$account_number\",
            \"accountName\": \"$account_name\",
            \"accountType\": \"ASSET\",
            \"initialBalance\": $initial_balance
        }" -s | jq . || echo "建立帳戶 $account_number 失敗"
}

# 檢查 Leader 是否就緒
leader_ready=false
for i in {1..30}; do
    if curl -s "http://localhost:8080/api/ledger/status" | grep -q "isLeader.*true"; then
        leader_ready=true
        break
    fi
    echo "等待 Leader 選舉完成... ($i/30)"
    sleep 2
done

if [ "$leader_ready" = true ]; then
    echo "✅ Leader 已就緒，創建測試帳戶..."
    create_account "ACC001" "張三" 1000.00
    create_account "ACC002" "李四" 500.00
    create_account "ACC003" "王五" 200.00
    echo "✅ 測試帳戶創建完成"
else
    echo "⚠️  Leader 尚未就緒，請稍後手動創建測試帳戶"
fi

echo ""
echo "🧪 測試命令："
echo "# 查詢所有帳戶"
echo "curl http://localhost:8080/api/ledger/accounts"
echo ""
echo "# 執行轉帳"
echo 'curl -X POST http://localhost:8080/api/ledger/transfer \'
echo '  -H "Content-Type: application/json" \'
echo '  -d '"'"'{"fromAccountNumber":"ACC001","toAccountNumber":"ACC002","amount":100.00,"description":"測試轉帳"}'"'"
echo ""
echo "# 查詢集群狀態"
echo "curl http://localhost:8080/api/ledger/status" 