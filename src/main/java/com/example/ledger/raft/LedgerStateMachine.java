package com.example.ledger.raft;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LedgerStateMachine extends StateMachineAdapter {
    
    @Override
    public void onApply(Iterator iter) {
        while (iter.hasNext()) {
            // TODO: Implement state machine logic
            iter.next();
        }
    }
    
    @Override
    public void onSnapshotSave(SnapshotWriter writer, Closure done) {
        log.info("Saving snapshot");
        done.run(Status.OK());
    }
    
    @Override
    public boolean onSnapshotLoad(SnapshotReader reader) {
        log.info("Loading snapshot");
        return true;
    }
    
    @Override
    public void onLeaderStart(long term) {
        log.info("Node becomes leader at term: {}", term);
        super.onLeaderStart(term);
    }
    
    @Override
    public void onLeaderStop(Status status) {
        log.info("Node stops being leader: {}", status);
        super.onLeaderStop(status);
    }
} 