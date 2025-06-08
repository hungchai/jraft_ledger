# 使用 OpenJDK 21 作為基礎映像
FROM openjdk:21-jdk-slim

# 設置工作目錄
WORKDIR /app

# 安裝必要的工具
RUN apt-get update && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 複製 Maven 文件
COPY pom.xml .
COPY src ./src

# 安裝 Maven
RUN apt-get update && apt-get install -y maven

# 編譯應用程式
RUN mvn clean package -DskipTests

# 創建資料目錄
RUN mkdir -p /app/raft_data

# 暴露端口
EXPOSE 8080 8081

# 設置 JVM 參數
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# 啟動應用程式
CMD ["sh", "-c", "java $JAVA_OPTS -jar target/jraft-ledger-system-1.0.0.jar"] 