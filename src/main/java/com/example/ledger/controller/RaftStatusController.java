package com.example.ledger.controller;

import com.example.ledger.raft.RaftNodeManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/raft")
@Tag(name = "Raft Status API", description = "Raft集群状态相关接口")
@ConditionalOnProperty(name = "raft.enabled", havingValue = "true")
public class RaftStatusController {

    @Autowired
    private RaftNodeManager raftNodeManager;

    @Value("${raft.node-id:node1}")
    private String nodeId;

    @Value("${raft.group-id:ledger-raft-group}")
    private String groupId;

    @Value("${raft.peers:127.0.0.1:8091,127.0.0.1:8092,127.0.0.1:8093}")
    private String peers;

    @Value("${raft.data-path:./raft-data}")
    private String dataPath;

    @GetMapping("/status")
    @Operation(summary = "获取Raft节点状态", description = "获取当前Raft节点的详细状态信息")
    public ResponseEntity<RaftStatusResponse> getRaftStatus() {
        log.info("Getting Raft node status");
        
        RaftStatusResponse response = new RaftStatusResponse();
        response.setNodeId(nodeId);
        response.setGroupId(groupId);
        response.setPeers(peers);
        response.setDataPath(dataPath);
        response.setTimestamp(LocalDateTime.now());
        
        // Check if JRaft node is actually running
        if (raftNodeManager.getNode() != null) {
            response.setStatus("ACTIVE");
            response.setEnabled(true);
            response.setMessage("JRaft node is running");
            // TODO: Add more detailed status when JRaft is enabled
            /*
            Node node = raftNodeManager.getNode();
            response.setLeader(node.getLeaderId() != null ? node.getLeaderId().toString() : "Unknown");
            response.setTerm(node.getCurrentTerm());
            response.setRole(node.isLeader() ? "LEADER" : "FOLLOWER");
            */
        } else {
            response.setStatus("DISABLED");
            response.setEnabled(false);
            response.setMessage("JRaft is temporarily disabled for compilation compatibility");
            response.setLeader("N/A");
            response.setTerm(0L);
            response.setRole("STANDALONE");
        }
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/config")
    @Operation(summary = "获取Raft配置", description = "获取当前Raft节点的配置信息")
    public ResponseEntity<Map<String, Object>> getRaftConfig() {
        log.info("Getting Raft configuration");
        
        Map<String, Object> config = new HashMap<>();
        config.put("nodeId", nodeId);
        config.put("groupId", groupId);
        config.put("peers", peers);
        config.put("dataPath", dataPath);
        config.put("enabled", raftNodeManager.getNode() != null);
        config.put("timestamp", LocalDateTime.now());
        
        return ResponseEntity.ok(config);
    }

    // DTO for Raft Status Response
    public static class RaftStatusResponse {
        private String nodeId;
        private String groupId;
        private String peers;
        private String dataPath;
        private String status;
        private boolean enabled;
        private String message;
        private String leader;
        private Long term;
        private String role;
        private LocalDateTime timestamp;

        // Getters and Setters
        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }

        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }

        public String getPeers() { return peers; }
        public void setPeers(String peers) { this.peers = peers; }

        public String getDataPath() { return dataPath; }
        public void setDataPath(String dataPath) { this.dataPath = dataPath; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getLeader() { return leader; }
        public void setLeader(String leader) { this.leader = leader; }

        public Long getTerm() { return term; }
        public void setTerm(Long term) { this.term = term; }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    }
} 