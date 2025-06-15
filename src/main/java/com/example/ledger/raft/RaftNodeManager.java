package com.example.ledger.raft;

import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.option.NodeOptions;
import com.example.ledger.config.RocksDBService;
import com.example.ledger.state.JRaftLedgerStateMachine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Slf4j
@Component
@ConditionalOnProperty(name = "raft.enabled", havingValue = "true")
public class RaftNodeManager {

    @Value("${raft.data-path:./raft-data}")
    private String dataPath;
    
    @Value("${raft.group-id:ledger-raft-group}")
    private String groupId;
    
    @Value("${raft.peers:127.0.0.1:8091,127.0.0.1:8092,127.0.0.1:8093}")
    private String peers;
    
    @Value("${raft.current-node.ip:127.0.0.1}")
    private String nodeIp;
    
    @Value("${raft.current-node.port:8091}")
    private int nodePort;
    
    @Value("${raft.election-timeout-ms:5000}")
    private int electionTimeoutMs;
    
    @Value("${raft.snapshot-interval-secs:30}")
    private int snapshotIntervalSecs;

    private Node node;
    private RaftGroupService raftGroupService;
    
    @Autowired
    private RocksDBService rocksDBService;
    
    @Autowired
    private JRaftLedgerStateMachine jraftLedgerStateMachine;

    @PostConstruct
    public void init() throws Exception {
        log.info("Initializing JRaft node with consensus enabled");
        try {
            NodeOptions nodeOptions = new NodeOptions();
            nodeOptions.setElectionTimeoutMs(electionTimeoutMs);
            nodeOptions.setDisableCli(false);
            nodeOptions.setSnapshotIntervalSecs(snapshotIntervalSecs);
            nodeOptions.setLogUri(dataPath + "/log");
            nodeOptions.setRaftMetaUri(dataPath + "/meta");
            nodeOptions.setSnapshotUri(dataPath + "/snapshot");

            // Set the JRaft state machine
            nodeOptions.setFsm(jraftLedgerStateMachine);

            // Parse cluster configuration
            Configuration conf = new Configuration();
            if (!conf.parse(peers)) {
                throw new IllegalArgumentException("Failed to parse peers: " + peers);
            }
            
            // Create server ID for this node
            PeerId serverId = new PeerId(nodeIp, nodePort);
            nodeOptions.setInitialConf(conf);

            // Start JRaft group service
            raftGroupService = new RaftGroupService(groupId, serverId, nodeOptions);
            this.node = raftGroupService.start();
            
            log.info("JRaft node started successfully: {}:{} in group: {}", nodeIp, nodePort, groupId);
        } catch (Exception e) {
            log.error("Failed to initialize JRaft node", e);
            throw e;
        }
    }

    public Node getNode() {
        return node;
    }
}
