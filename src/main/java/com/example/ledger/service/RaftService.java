package com.example.ledger.service;

import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.option.NodeOptions;
import com.alipay.sofa.jraft.rpc.RaftRpcServerFactory;
import com.alipay.sofa.jraft.rpc.RpcServer;
import com.alipay.sofa.jraft.util.Endpoint;
import com.example.ledger.dto.TransferRequest;
import com.example.ledger.raft.LedgerStateMachine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RaftService {

    @Value("${raft.node.id}")
    private String nodeId;

    @Value("${raft.node.address}")
    private String nodeAddress;

    @Value("${raft.group.id}")
    private String groupId;

    @Value("${raft.initial.conf}")
    private String initialConf;

    private Node node;
    private RpcServer rpcServer;
    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private Endpoint leaderEndpoint;

    @PostConstruct
    public void init() {
        try {
            // Parse node address
            String[] addressParts = nodeAddress.split(":");
            String host = addressParts[0];
            int port = Integer.parseInt(addressParts[1]);
            Endpoint endpoint = new Endpoint(host, port);

            // Create RPC server
            rpcServer = RaftRpcServerFactory.createRaftRpcServer(endpoint);

            // Create state machine
            LedgerStateMachine stateMachine = new LedgerStateMachine();

            // Configure node options
            NodeOptions nodeOptions = new NodeOptions();
            nodeOptions.setElectionTimeoutMs(5000);
            nodeOptions.setDisableCli(false);
            nodeOptions.setSnapshotIntervalSecs(3600);
            nodeOptions.setFsm(stateMachine);

            // Set storage paths
            nodeOptions.setLogUri("raft/log/" + nodeId);
            nodeOptions.setRaftMetaUri("raft/meta/" + nodeId);
            nodeOptions.setSnapshotUri("raft/snapshot/" + nodeId);

            // Parse initial configuration
            Configuration conf = new Configuration();
            if (!conf.parse(initialConf)) {
                throw new RuntimeException("Failed to parse initial configuration: " + initialConf);
            }
            nodeOptions.setInitialConf(conf);

            // Create and start Raft group service
            RaftGroupService raftGroupService = new RaftGroupService(groupId, new PeerId(endpoint, 0), nodeOptions, rpcServer);
            node = raftGroupService.start();

            // Start RPC server
            rpcServer.init(null);

            System.out.println("Raft node started: " + nodeId + " at " + nodeAddress);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Raft node", e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (node != null) {
            node.shutdown();
        }
        if (rpcServer != null) {
            rpcServer.shutdown();
        }
    }

    public boolean isLeader() {
        return isLeader.get();
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getLeaderEndpoint() {
        return leaderEndpoint != null ? leaderEndpoint.toString() : null;
    }

    public CompletableFuture<Boolean> submitTransfer(TransferRequest request) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (!isLeader()) {
            future.complete(false);
            return future;
        }

        // TODO: Implement transfer submission to Raft cluster
        future.complete(true);
        return future;
    }

    public CompletableFuture<Boolean> submitBatchTransfer(TransferRequest[] requests) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (!isLeader()) {
            future.complete(false);
            return future;
        }

        // TODO: Implement batch transfer submission to Raft cluster
        future.complete(true);
        return future;
    }

    public void onLeaderStart() {
        isLeader.set(true);
        System.out.println("Node " + nodeId + " became leader");
    }

    public void onLeaderStop() {
        isLeader.set(false);
        System.out.println("Node " + nodeId + " stopped being leader");
    }

    public void onLeaderChange(Endpoint newLeader) {
        leaderEndpoint = newLeader;
        System.out.println("New leader: " + newLeader);
    }
} 