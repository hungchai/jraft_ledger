package com.tomma8.ledger.raft.ratis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.RaftCommand;
import com.tomma8.ledger.raft.CommandSerializer;
import com.tomma8.ledger.raft.ConsensusEngine;
import com.tomma8.ledger.statemachine.LedgerStateMachine;
import com.tomma8.ledger.util.LedgerMappers;
import org.apache.ratis.RaftConfigKeys;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Apache Ratis consensus engine — alternative to {@code RaftNodeManager} (SOFAJRaft).
 *
 * <p>Mirrors the {@link ConsensusEngine} surface: lifecycle ({@link #init()} / {@link #close()}),
 * command submission ({@link #submit}), and leader/role queries. The wrapped
 * {@link RatisLedgerStateMachine} delegates all business logic to the shared
 * {@link LedgerStateMachine}.
 *
 * <p>Submission uses an embedded {@link RaftClient} (Ratis has no in-JVM {@code node.apply()}
 * equivalent). {@code client.io().send()} blocks until the entry is committed to a quorum and
 * applied on the leader; the applied {@link CommandResult} is returned in the reply message.
 * This adds one local RPC hop vs SOFAJRaft — a real and reported comparison datapoint.
 */
public class RatisNodeManager implements ConsensusEngine {

    private static final Logger log = LoggerFactory.getLogger(RatisNodeManager.class);

    private final RaftPeerId selfId;
    private final RaftGroup raftGroup;
    private final RaftGroupId groupId;
    private final RaftProperties properties;
    private final RatisLedgerStateMachine stateMachine;

    // CommandResult is serialized into the reply via LedgerMappers (which also emits the
    // isCompleted()/isRejected() derived getters as "completed"/"rejected"); read leniently
    // so those derived fields are ignored on the way back.
    private static final ObjectMapper RESULT_READER = LedgerMappers.get().copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private RaftServer server;
    private RaftClient client;

    /**
     * @param groupName  logical group name; mapped to a deterministic UUID shared by all nodes
     * @param selfPeerId this node's peer id (e.g. "node1")
     * @param peers      comma-separated "id:host:raftPort" entries for the full membership
     * @param dataPath   storage dir root for Ratis log + snapshots
     * @param raftPort   gRPC server port for this node
     */
    public RatisNodeManager(String groupName, String selfPeerId, String peers,
                            String dataPath, int raftPort,
                            RatisLedgerStateMachine stateMachine) {
        this.stateMachine = stateMachine;
        this.selfId = RaftPeerId.valueOf(selfPeerId);
        this.groupId = RaftGroupId.valueOf(UUID.nameUUIDFromBytes(groupName.getBytes(StandardCharsets.UTF_8)));

        // Peer format: "id:host:raftPort" (preferred), "id:raftPort" (id doubles as host),
        // or bare "id" (host=id, port=raftPort).
        List<RaftPeer> peerList = new ArrayList<>();
        for (String p : peers.split(",")) {
            String[] parts = p.trim().split(":");
            String id, host, port;
            if (parts.length >= 3) {
                id = parts[0]; host = parts[1]; port = parts[2];
            } else if (parts.length == 2) {
                id = parts[0]; host = parts[0]; port = parts[1];
            } else {
                id = parts[0]; host = parts[0]; port = String.valueOf(raftPort);
            }
            peerList.add(RaftPeer.newBuilder().setId(id).setAddress(host + ":" + port).build());
        }
        this.raftGroup = RaftGroup.valueOf(groupId, peerList);

        this.properties = new RaftProperties();
        RaftConfigKeys.Rpc.setType(properties, SupportedRpcType.GRPC);
        GrpcConfigKeys.Server.setPort(properties, raftPort);
        RaftServerConfigKeys.setStorageDir(properties,
                Collections.singletonList(new File(dataPath)));
    }

    public boolean init() {
        try {
            new File(propertiesStorageDir()).mkdirs();
            this.server = RaftServer.newBuilder()
                    .setServerId(selfId)
                    .setGroup(raftGroup)
                    .setProperties(properties)
                    .setStateMachine(stateMachine)
                    .build();
            this.server.start();
            this.client = RaftClient.newBuilder()
                    .setProperties(properties)
                    .setRaftGroup(raftGroup)
                    .setClientId(ClientId.randomId())
                    .build();
            log.info("Ratis node started: {} group={}", selfId, groupId);
            return true;
        } catch (Exception e) {
            log.error("Failed to start Ratis node {}: {}", selfId, e.getMessage(), e);
            throw new RuntimeException("Ratis start failed", e);
        }
    }

    private String propertiesStorageDir() {
        return RaftServerConfigKeys.storageDir(properties).get(0).getAbsolutePath();
    }

    @Override
    public CommandResult submit(RaftCommand command) {
        // Stamp the apply timestamp once, here on the leader, so it replicates
        // inside the log entry and every node applies with the same time.
        byte[] data = CommandSerializer.serialize(command, System.currentTimeMillis());
        try {
            RaftClientReply reply = client.io().send(Message.valueOf(ByteString.copyFrom(data)));
            if (!reply.isSuccess()) {
                throw new RuntimeException("Ratis submit failed: " + reply.getException());
            }
            ByteString content = reply.getMessage().getContent();
            return RESULT_READER.readValue(content.toByteArray(), CommandResult.class);
        } catch (Exception e) {
            throw new RuntimeException("Ratis command failed: " + command.requestId(), e);
        }
    }

    @Override
    public boolean isLeader() {
        return stateMachine.isLeader();
    }

    @Override
    public String getLeaderEndpoint() {
        try {
            RaftPeerId leader = server.getDivision(groupId).getInfo().getLeaderId();
            return leader != null ? leader.toString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public long getLastAppliedIndex() {
        return stateMachine.getLastAppliedTermIndex() != null
                ? stateMachine.getLastAppliedTermIndex().getIndex() : 0L;
    }

    @Override
    public LedgerStateMachine getLedgerStateMachine() {
        return stateMachine.getLedgerStateMachine();
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    @Override
    public List<String> getAlivePeers() {
        return raftGroup.getPeers().stream().map(p -> p.getId().toString()).toList();
    }

    @Override
    public void close() {
        try {
            if (client != null) client.close();
        } catch (Exception e) {
            log.warn("Ratis client close error: {}", e.getMessage());
        }
        try {
            if (server != null) server.close();
        } catch (Exception e) {
            log.warn("Ratis server close error: {}", e.getMessage());
        }
    }
}
