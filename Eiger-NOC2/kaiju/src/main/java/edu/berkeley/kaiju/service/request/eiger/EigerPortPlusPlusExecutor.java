package edu.berkeley.kaiju.service.request.eiger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Vector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import com.yammer.metrics.Histogram;
import com.yammer.metrics.MetricRegistry;
import com.yammer.metrics.Timer;

import edu.berkeley.kaiju.config.Config;
import edu.berkeley.kaiju.data.DataItem;
import edu.berkeley.kaiju.data.Transaction;
import edu.berkeley.kaiju.exception.ClientException;
import edu.berkeley.kaiju.exception.KaijuException;
import edu.berkeley.kaiju.monitor.MetricsManager;
import edu.berkeley.kaiju.net.routing.OutboundRouter;
import edu.berkeley.kaiju.service.MemoryStorageEngine;
import edu.berkeley.kaiju.service.request.RequestDispatcher;
import edu.berkeley.kaiju.service.request.message.KaijuMessage;
import edu.berkeley.kaiju.service.request.message.request.EigerCheckCommitRequest;
import edu.berkeley.kaiju.service.request.message.request.EigerCommitRequest;
import edu.berkeley.kaiju.service.request.message.request.EigerGetAllRequest;
import edu.berkeley.kaiju.service.request.message.request.EigerPutAllRequest;
import edu.berkeley.kaiju.service.request.message.request.PutAllRequest;
import edu.berkeley.kaiju.service.request.message.response.EigerPreparedResponse;
import edu.berkeley.kaiju.service.request.message.response.KaijuResponse;
import edu.berkeley.kaiju.util.Timestamp;

public class EigerPortPlusPlusExecutor implements IEigerExecutor{
    private static Logger logger = LoggerFactory.getLogger(EigerExecutor.class);

    private RequestDispatcher dispatcher;
    private MemoryStorageEngine storageEngine;

    private ConcurrentMap<Long, EigerPendingTransaction> pendingTransactionsCoordinated = Maps.newConcurrentMap();
    private ConcurrentMap<Long, EigerPutAllRequest> pendingTransactionsNonCoordinated = Maps.newConcurrentMap();

    ReentrantLock pendingTransactionsLock = new ReentrantLock();
    private ConcurrentSkipListSet<Long> pending = new ConcurrentSkipListSet<Long>();
    private ConcurrentMap<Long,Long> tidToPendingTime = Maps.newConcurrentMap();
    // a roughly time-ordered queue of KVPs to GC; exact real-time ordering not necessary for correctness
    private BlockingQueue<CommittedGarbage> candidatesForGarbageCollection = Queues.newLinkedBlockingQueue();
    Long lst = Timestamp.NO_TIMESTAMP;
    Long latest_commit = Timestamp.NO_TIMESTAMP;
    public EigerPortPlusPlusExecutor(RequestDispatcher dispatcher,
                         MemoryStorageEngine storageEngine) {
        this.dispatcher = dispatcher;
        this.storageEngine = storageEngine;

        new Thread(new Runnable() {
                    @Override
                    public void run() {
                        long currentTime = -1;
                        CommittedGarbage nextStamp = null;
                        while(true) {
                            try {
                                if(nextStamp == null)
                                    nextStamp = candidatesForGarbageCollection.take();
                                if(nextStamp.getExpirationTime() < currentTime ||
                                   (nextStamp.getExpirationTime() < (currentTime = System.currentTimeMillis())) ) {
                                    pendingTransactionsCoordinated.remove(nextStamp.getTimestamp());
                                    nextStamp = null;
                                } else {
                                    Thread.sleep(nextStamp.getExpirationTime()-currentTime);
                                }
                            } catch (InterruptedException e) {}
                        }
                    }
                }, "Eiger-GC-Thread").start();
    }
    
    @Override
    public void processMessage(EigerPutAllRequest putAllRequest)
            throws KaijuException, IOException, InterruptedException {

        if(putAllRequest.is_get) getAll(putAllRequest);
        else putAll(putAllRequest);
    }

    public void putAll(EigerPutAllRequest putAllRequest)throws KaijuException, IOException, InterruptedException{
        long transactionID = putAllRequest.keyValuePairs.values().iterator().next().getTimestamp();
        if(OutboundRouter.ownsResource(putAllRequest.coordinatorKey.hashCode())) {
            if(!pendingTransactionsCoordinated.containsKey(transactionID)) {
                pendingTransactionsCoordinated.putIfAbsent(transactionID, new EigerPendingTransaction());
            }

            pendingTransactionsCoordinated.get(transactionID).setCoordinatorState(putAllRequest.totalNumKeys,
                                                                                  putAllRequest.senderID,
                                                                                  putAllRequest.requestID);
        }

        assert(!pendingTransactionsNonCoordinated.containsKey(transactionID));
        pendingTransactionsNonCoordinated.put(transactionID, putAllRequest);
        Long pending_t = Timestamp.assignNewTimestamp();
        pending.add(pending_t);
        tidToPendingTime.putIfAbsent(transactionID, pending_t);
        Long prepared_t = Timestamp.assignNewTimestamp();
        dispatcher.requestOneWay(putAllRequest.coordinatorKey.hashCode(), new EigerPreparedResponse(transactionID,
                                                                                                    putAllRequest
                                                                                                            .keyValuePairs
                                                                                                            .size(),
                                                                                                    prepared_t));
        KaijuResponse response = new KaijuResponse();
        response.setHct(this.lst);
        response.setPrepTs(prepared_t);
        dispatcher.sendResponse(putAllRequest.senderID, putAllRequest.requestID, response);
    }

    public void getAllTester(EigerPutAllRequest getAllRequest)throws KaijuException, IOException, InterruptedException{
        Map<String,DataItem> result = new HashMap<String,DataItem>();
        for(Map.Entry<String,DataItem> entry : getAllRequest.keyValuePairs.entrySet()){
            Long prepTs = entry.getValue().getPrepTs();
            Long version = storageEngine.getHighestCommittedNotGreaterThan(entry.getKey(),entry.getValue().getTimestamp());
            Long latestByClient = storageEngine.getHighestCommittedPerCid(entry.getKey(), entry.getValue().getCid(), version);
            if(prepTs > version && prepTs > latestByClient){
                if(pendingTransactionsNonCoordinated.containsKey(entry.getValue().getTid())){
                    DataItem prepData = pendingTransactionsNonCoordinated.get(entry.getValue().getTid()).keyValuePairs.get(entry.getKey());
                    result.put(entry.getKey(), prepData);
                    logTransaction(entry.getKey(), prepTs, entry.getValue().getCid(),Long.valueOf(getAllRequest.requestID), "READ");
                }else{
                    result.put(entry.getKey(), storageEngine.getByTimestamp(entry.getKey(), prepTs));
                    logTransaction(entry.getKey(), prepTs, entry.getValue().getCid(),Long.valueOf(getAllRequest.requestID), "READ");
                }
                
            }else if(latestByClient != Timestamp.NO_TIMESTAMP){
                result.put(entry.getKey(), storageEngine.getByTimestamp(entry.getKey(), latestByClient));
                logTransaction(entry.getKey(), latestByClient, entry.getValue().getCid(),Long.valueOf(getAllRequest.requestID), "READ");
            }else{
                DataItem ver = storageEngine.getByTimestamp(entry.getKey(), version);
                if(version == Timestamp.NO_TIMESTAMP || ver.getTimestamp() == Timestamp.NO_TIMESTAMP){
                    result.put(entry.getKey(), DataItem.getNullItem());
                }else{
                    result.put(entry.getKey(), ver);
                    logTransaction(entry.getKey(), ver.getTimestamp(), entry.getValue().getCid(),Long.valueOf(getAllRequest.requestID), "READ");
                }
            }
            logFreshness(entry.getKey(), result.get(entry.getKey()));
        }
        KaijuResponse response = new KaijuResponse(result);
        response.setHct(this.lst);
        dispatcher.sendResponse(getAllRequest.senderID, getAllRequest.requestID, response);
    }

    public void getAll(EigerPutAllRequest getAllRequest)throws KaijuException, IOException, InterruptedException{
        if(Config.getConfig().ra_tester == 1){
            getAllTester(getAllRequest);
            return;
        }
        Map<String,DataItem> result = new HashMap<String,DataItem>();
        for(Map.Entry<String,DataItem> entry : getAllRequest.keyValuePairs.entrySet()){
            Long prepTs = entry.getValue().getPrepTs();
            Long version = storageEngine.getHighestCommittedNotGreaterThan(entry.getKey(),entry.getValue().getTimestamp());
            Long latestByClient = storageEngine.getHighestCommittedPerCid(entry.getKey(), entry.getValue().getCid(), version);
            if(prepTs > version && prepTs > latestByClient){
                if(pendingTransactionsNonCoordinated.containsKey(entry.getValue().getTid())){
                    DataItem prepData = pendingTransactionsNonCoordinated.get(entry.getValue().getTid()).keyValuePairs.get(entry.getKey());
                    result.put(entry.getKey(), prepData);
                }else{
                    result.put(entry.getKey(), storageEngine.getByTimestamp(entry.getKey(), prepTs));
                }
                
            }else if(latestByClient != Timestamp.NO_TIMESTAMP){
                result.put(entry.getKey(), storageEngine.getByTimestamp(entry.getKey(), latestByClient));
            }else{
                DataItem ver = storageEngine.getByTimestamp(entry.getKey(), version);
                if(version == Timestamp.NO_TIMESTAMP || ver.getTimestamp() == Timestamp.NO_TIMESTAMP){
                    result.put(entry.getKey(), DataItem.getNullItem());
                }else{
                    result.put(entry.getKey(), ver);
                }
            }
            logFreshness(entry.getKey(), result.get(entry.getKey()));
        }
        KaijuResponse response = new KaijuResponse(result);
        response.setHct(this.lst);
        dispatcher.sendResponse(getAllRequest.senderID, getAllRequest.requestID, response);
    }

    @Override
    public void processMessage(EigerPreparedResponse preparedNotification)
            throws KaijuException, IOException, InterruptedException {
                if(!pendingTransactionsCoordinated.containsKey(preparedNotification.transactionID)) {
                    EigerPendingTransaction newTxn =new EigerPendingTransaction();
                    pendingTransactionsCoordinated.putIfAbsent(preparedNotification.transactionID, newTxn);
                }
        
                EigerPendingTransaction ept = pendingTransactionsCoordinated.get(preparedNotification.transactionID);
        
                ept.recordPreparedKeys(preparedNotification.senderID, preparedNotification.numKeys, preparedNotification.preparedTime);
        
                if(ept.shouldCommit()) {
                    commitEigerPendingTransaction(preparedNotification.transactionID, ept);
                }
        
    }

    private void commitEigerPendingTransaction(long transactionID, EigerPendingTransaction ept) throws IOException, InterruptedException{
        Map<Integer, KaijuMessage> toSend = Maps.newHashMap();
        for(int serverToNotify : ept.getServersToNotifyCommit()) {
            toSend.put(serverToNotify, new EigerCommitRequest(transactionID, ept.getCommitTime()));
        }
        Long commitTime = ept.getCommitTime();
        dispatcher.multiRequestOneWay(toSend);
        if(tidToPendingTime.containsKey(transactionID) && pending.contains(tidToPendingTime.get(transactionID))){
            pending.remove(tidToPendingTime.get(transactionID));
            tidToPendingTime.remove(transactionID);
        }
        if(commitTime > this.latest_commit) this.latest_commit = commitTime;
        Long tmp = this.pending.pollFirst();
        if(tmp == null) this.lst = this.latest_commit;
        else this.lst = tmp;
        candidatesForGarbageCollection.add(new CommittedGarbage(transactionID, System.currentTimeMillis()+Config.getConfig().overwrite_gc_ms));
    }
    
    @Override
    public void processMessage(EigerCommitRequest commitNotification)
            throws KaijuException, IOException, InterruptedException {
        nonCoordinatorMarkCommitted(commitNotification.transactionID, commitNotification.commitTime);
        
    }

    private void nonCoordinatorMarkCommitted(long transactionID, Long commitTime) throws KaijuException, IOException {
        EigerPutAllRequest preparedRequest = pendingTransactionsNonCoordinated.get(transactionID);

        if(preparedRequest == null) {
            return;
        }

        Map<String, DataItem> toCommit = Maps.newHashMap();

        for(String key : preparedRequest.keyValuePairs.keySet()) {
            DataItem item = preparedRequest.keyValuePairs.get(key);
            DataItem new_item =  new DataItem(commitTime, item.getValue());
            new_item.setCid(item.getCid());
            new_item.setPrepTs(item.getPrepTs());
            toCommit.put(key,new_item);

            //logger.info(String.format("%d: COMMITTING %s [%s] at time %d\n", Config.getConfig().server_id, key, Arrays.toString(item.getValue().array()), commitNotification.commitTime));
        }

        storageEngine.putAll(toCommit);
        if(!OutboundRouter.ownsResource(preparedRequest.coordinatorKey.hashCode())){
            if(tidToPendingTime.containsKey(transactionID) && pending.contains(tidToPendingTime.get(transactionID))){
                pending.remove(tidToPendingTime.get(transactionID));
                tidToPendingTime.remove(transactionID);
            }
            if(commitTime > this.latest_commit) this.latest_commit = commitTime;
            Long tmp = this.pending.pollFirst();
            if(tmp == null) this.lst = this.latest_commit;
            else this.lst = tmp;
        }
        if(Config.getConfig().ra_tester == 1){
            for(String key : preparedRequest.keyValuePairs.keySet()){
                DataItem item = preparedRequest.keyValuePairs.get(key);
                logTransaction(key, commitTime, item.getCid(), transactionID, "WRITE");
            }
        }
    }

    @Override
    public void processMessage(EigerGetAllRequest getAllRequest)
            throws KaijuException, IOException, InterruptedException {
        throw new ClientException("This method should not be reached in Eiger-PORT");
    }

    @Override
    public void processMessage(EigerCheckCommitRequest checkCommitRequest)
            throws KaijuException, IOException, InterruptedException {
        throw new ClientException("This method should not be reached in Eiger-PORT");
    }

    class EigerPendingTransaction {
        private AtomicInteger numKeysSeen;
        private int numKeysWaiting;
        private Vector<Integer> serversToNotifyCommit = new Vector<Integer>();
        private int clientID = -1;
        private int clientRequestID = -1;
        AtomicBoolean readyToCommit = new AtomicBoolean(false);
        AtomicBoolean committed = new AtomicBoolean(false);

        private long highestPreparedTime = -1;

        ReentrantLock commitTimeLock = new ReentrantLock();

        public EigerPendingTransaction() {
            this.numKeysSeen = new AtomicInteger(0);
        }

        public void setCoordinatorState(int numKeysWaiting, int clientID, int clientRequestID) {
            this.numKeysWaiting = numKeysWaiting;
            this.clientID = clientID;
            this.clientRequestID = clientRequestID;
        }

        public synchronized boolean shouldCommit() {
            boolean ret = readyToCommit.getAndSet(false);
            if(ret)
                committed.set(true);
            return ret;
        }

        public synchronized boolean hasCommitted() {
            return committed.get();
        }

        public long getCommitTime() {
            return highestPreparedTime;
        }

        public Collection<Integer> getServersToNotifyCommit() {
            return serversToNotifyCommit;
        }

        public int getClientID() {
            assert (clientID != -1);
            return clientID;
        }

        public int getClientRequestID() {
            assert (clientRequestID != -1);
            return clientRequestID;
        }

        public synchronized void recordPreparedKeys(int server, int numKeys, long preparedTime) {
            if(highestPreparedTime < preparedTime)
                highestPreparedTime = preparedTime;
            serversToNotifyCommit.add(server);
            numKeysSeen.getAndAdd(numKeys);

            if(numKeysSeen.get() == numKeysWaiting)
                readyToCommit.set(true);
        }

    }

    private class CommittedGarbage {
           private long timestamp;
           private long expirationTime = -1;

           public CommittedGarbage(long timestamp, long expirationTime) {
               this.timestamp = timestamp;
               this.expirationTime = expirationTime;
           }

           public long getExpirationTime(){
               return expirationTime;
           }

           private long getTimestamp() {
               return timestamp;
           }

           @Override
           public int hashCode() {
               return Long.valueOf(timestamp).hashCode();
           }

           @Override
           public boolean equals(Object obj) {
               if (obj == null)
                    return false;
                if (obj == this)
                    return true;
                if (!(obj instanceof CommittedGarbage))
                    return false;

                CommittedGarbage rhs = (CommittedGarbage) obj;
                return rhs.getTimestamp() == timestamp ;
           }
       }
    @Override
    public void logFreshness(String key, DataItem value) {
        if(Config.getConfig().freshness_test == 0) return;
        Long freshness = storageEngine.freshness(key, value.getTimestamp());
        //logger.warn("Freshness for key: " + key + " timestamp: " + value.getTimestamp() + " = " + freshness);
    }

    @Override
    public void logTransaction(String key, Long timestamp, String client_id, Long transaction_id, String type) {
        if(timestamp == Timestamp.NO_TIMESTAMP) return;
        Transaction t = new Transaction(key, timestamp, client_id, transaction_id, type);
        storageEngine.test.add(t);
    }

}
