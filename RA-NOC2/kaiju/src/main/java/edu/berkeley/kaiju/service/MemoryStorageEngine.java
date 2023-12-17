package edu.berkeley.kaiju.service;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Queues;
import com.yammer.metrics.Gauge;
import com.yammer.metrics.Meter;
import com.yammer.metrics.MetricRegistry;

import edu.berkeley.kaiju.KaijuServer;
import edu.berkeley.kaiju.config.Config;
import edu.berkeley.kaiju.config.Config.IsolationLevel;
import edu.berkeley.kaiju.config.Config.ReadAtomicAlgorithm;
import edu.berkeley.kaiju.data.DataItem;
import edu.berkeley.kaiju.data.ItemVersion;
import edu.berkeley.kaiju.exception.AbortedException;
import edu.berkeley.kaiju.exception.HandlerException;
import edu.berkeley.kaiju.exception.KaijuException;
import edu.berkeley.kaiju.monitor.MetricsManager;
import edu.berkeley.kaiju.net.routing.OutboundRouter;
import edu.berkeley.kaiju.service.request.RequestDispatcher;
import edu.berkeley.kaiju.service.request.message.KaijuMessage;
import edu.berkeley.kaiju.service.request.message.request.PreparePutAllRequest;
import edu.berkeley.kaiju.service.request.message.response.KaijuResponse;
import edu.berkeley.kaiju.util.KeyCidPair;
import edu.berkeley.kaiju.util.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Time;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;
import java.util.Map.Entry;

/*
 Fairly simple in-memory KVS with limited support for multiversioning.

 The annoying bit comes in implementing so many different algorithms, each of which requires accesses to
 various indexes.
 */
public class MemoryStorageEngine {

    private Meter overwrittenMeter = MetricsManager.getRegistry().meter(MetricRegistry.name(MemoryStorageEngine.class,
                                                                                            "put-requests",
                                                                                            "overwrites"));

    private Meter nopWriteMeter = MetricsManager.getRegistry().meter(MetricRegistry.name(MemoryStorageEngine.class,
                                                                                         "put-requests",
                                                                                         "no-overwrites"));

    private Meter gcWriteMeter = MetricsManager.getRegistry().meter(MetricRegistry.name(MemoryStorageEngine.class,
                                                                                        "gc-request",
                                                                                        "gc-events"));

    private Gauge<Integer> gcQueueSize = MetricsManager.getRegistry().register(MetricRegistry.name(MemoryStorageEngine.class,
                                                                                                   "gc-queue",
                                                                                                   "size"),
                                                                               new Gauge<Integer>() {
                                                                                   @Override
                                                                                   public Integer getValue() {
                                                                                       return candidatesForGarbageCollection

                                                                                               .size();
                                                                                   }
                                                                               });

    private Gauge<Integer> numVersions = MetricsManager.getRegistry().register(MetricRegistry.name(MemoryStorageEngine.class,
                                                                                                   "datastore",
                                                                                                   "version-count"),
                                                                                   new Gauge<Integer>() {
                                                                                       @Override
                                                                                       public Integer getValue() {
                                                                                           return dataItems.size();
                                                                                       }
                                                                                   });

    private Gauge<Integer> numKeys = MetricsManager.getRegistry().register(MetricRegistry.name(MemoryStorageEngine.class,
                                                                                                   "datastore",
                                                                                                   "key-count"),
                                                                                   new Gauge<Integer>() {
                                                                                       @Override
                                                                                       public Integer getValue() {
                                                                                           return lastCommitForKey.size();
                                                                                       }
                                                                                   });



    private static final long gcTimeMs = Config.getConfig().overwrite_gc_ms;
    private static final long gcTimePrepMs = Config.getConfig().overwrite_gc_prep_ms;
                                                                                   

    public static Logger logger = LoggerFactory.getLogger(MemoryStorageEngine.class);

    /*
     We basically implement the KVS as a map from [Key, Timestamp] -> value, with an index on
     Key -> Last Committed Timestamp.

     We experimented with NonBlockingHashMap, but didn't have much luck.
     */

    // 'versions' in the pseudocode; using KeyTimestampPair helper makes GC easier than nesting maps
    private ConcurrentMap<KeyTimestampPair, DataItem> dataItems = Maps.newConcurrentMap();

    // 'lastCommit' in the pseudocode
    private ConcurrentMap<String, Long> lastCommitForKey = Maps.newConcurrentMap();

    // when we get a 'commit' message, this map tells us which [Key, Timestamp] pairs were actually committed
    private ConcurrentMap<Long, List<KeyTimestampPair>> preparedNotCommittedByStamp = Maps.newConcurrentMap();
                                                                                
    // Replication:
    private BlockingQueue<KeyTimestampPair> toReplicate = Queues.newLinkedBlockingQueue();
    private RequestDispatcher dispatcher;                                                                            
    
    private boolean tests = (Config.getConfig().freshness_test == 1 || Config.getConfig().ra_tester == 1);

    // only used in E-PCI, which requires ordering for lookups
    private Map<String, ConcurrentSkipListMap<Long, DataItem>> eigerMap = Maps.newConcurrentMap();

    // used in CTP
    private ConcurrentMap<Long, Boolean> abortedTxns = Maps.newConcurrentMap();

    private boolean isEiger = Config.getConfig().isolation_level == IsolationLevel.EIGER;

    // a roughly time-ordered queue of KVPs to GC; exact real-time ordering not necessary for correctness
    private BlockingQueue<KeyTimestampPair> candidatesForGarbageCollection = Queues.newLinkedBlockingQueue();
    
    // used for freshness:
    public ConcurrentMap<KeyTimestampPair,Long> timesPerVersion = Maps.newConcurrentMap();
    public ConcurrentMap<String,Long> latestTime = Maps.newConcurrentMap();

    // ORA:
    private long latest = Timestamp.NO_TIMESTAMP;
    private long latest_prep = Timestamp.NO_TIMESTAMP;
    private Map<KeyCidPair, Long> keyCidVersions = Maps.newConcurrentMap();
    private ConcurrentSkipListSet<Long> prep = new ConcurrentSkipListSet<Long>();

    public MemoryStorageEngine() {
        // GC old versions
        new Thread(new Runnable() {
            @Override
            public void run() {
                long currentTime = -1;
                KeyTimestampPair nextStamp = null;
                while(true) {
                    try {
                        if(nextStamp == null)
                            nextStamp = candidatesForGarbageCollection.take();
                        if(nextStamp.getExpirationTime() < currentTime ||
                           (nextStamp.getExpirationTime() < (currentTime = System.currentTimeMillis())) ) {
                            dataItems.remove(nextStamp);

                            if(isEiger || MemoryStorageEngine.is_ORA() || MemoryStorageEngine.is_NOC())
                                if(eigerMap.containsKey(nextStamp.getKey()))
                                    eigerMap.get(nextStamp.getKey()).remove(nextStamp.getTimestamp());
                            if(preparedNotCommittedByStamp.containsKey(nextStamp.getTimestamp())){
                                preparedNotCommittedByStamp.remove(nextStamp.getTimestamp());
                                prep.remove(nextStamp.getTimestamp());
                            }
                            gcWriteMeter.mark();
                            nextStamp = null;

                            
                        } else {
                            Thread.sleep(nextStamp.getExpirationTime()-currentTime);
                        }
                    } catch (InterruptedException e) {}
                }
            }
        }, "Storage-GC-Thread").start();
        new Thread(new Runnable(){

            @Override
            public void run() {
                if(Config.getConfig().replication == 0) return;
                while(true){
                    try {
                        Thread.sleep(100);
                        int i = 0;
                        Map<String,DataItem> itemsToReplicate = Maps.newConcurrentMap();

                        while(i < Config.getConfig().batch_size_replication){
                            if(toReplicate.isEmpty()) continue;
                            KeyTimestampPair item = toReplicate.take();
                            itemsToReplicate.put(item.key, getItemByVersion(item.key, item.timestamp));
                            i++;
                        }
                        if(itemsToReplicate.isEmpty()) continue;

                        Map<Integer, Collection<String>> keysByServerID = OutboundRouter.getRouter().groupKeysByReplicaServerID(itemsToReplicate.keySet());
                        Map<Integer, KaijuMessage> requestsByServerID = Maps.newHashMap();

                        for(int serverID : keysByServerID.keySet()) {
                            Map<String, DataItem> keyValuePairsForServer = Maps.newHashMap();
                            for(String key : keysByServerID.get(serverID)) {
                                keyValuePairsForServer.put(key, itemsToReplicate.get(key));
                                keyValuePairsForServer.get(key).setCid("replica");
                            }
                            requestsByServerID.put(serverID, new PreparePutAllRequest(keyValuePairsForServer));
                        }

                        Collection<KaijuResponse> responses = dispatcher.multiRequest(requestsByServerID);

                        KaijuResponse.coalesceErrorsIntoException(responses);        
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

        }, "Replication-Thread").start();
    }

    public void setDispatcher(RequestDispatcher dispatcher){
        // used to send replication messages to the replica
        this.dispatcher = dispatcher;
    }

    public void replicaPutAll(Map<String,DataItem> items){
        // batch replication
        logger.warn("replicating " + Integer.toString(items.size()) + " items");
        for( Entry<String, DataItem> item : items.entrySet() ){
             dataItems.put(new KeyTimestampPair(item.getKey(), item.getValue().getTimestamp()), item.getValue());
        }
    }

    //freshness functions:
    public long freshness(String key, long timestamp){
        if(timestamp == Timestamp.NO_TIMESTAMP) return 0;
        KeyTimestampPair kts = this.createNewKeyTimestampPair(key, timestamp);
        if(!this.timesPerVersion.containsKey(kts) || !this.latestTime.containsKey(key)) return 0;
        return this.latestTime.get(key) - this.timesPerVersion.get(kts);
    }

    public long freshness_ORA(String key, long timestamp, long late){
        if(timestamp == Timestamp.NO_TIMESTAMP) return 0;
        KeyTimestampPair kts = this.createNewKeyTimestampPair(key, timestamp);
        if(!this.timesPerVersion.containsKey(kts) || late == -1) return 0;
        long f = late - this.timesPerVersion.get(kts);
        if(f < 0) return 0;
        else return f;
    }

    public long getHighestCommittedNotGreaterThan(String key, long timestamp, long prepTimestamp){
        if(!this.eigerMap.containsKey(key)) return Timestamp.NO_TIMESTAMP;
        Map.Entry<Long,DataItem> res = this.eigerMap.get(key).floorEntry(timestamp);
        if(res == null || (prepTimestamp > res.getValue().getTimestamp() && this.dataItems.containsKey(createNewKeyTimestampPair(key, prepTimestamp)))){
            return prepTimestamp;
        }
        return res.getValue().getTimestamp();
    }

    public long getHighestCommittedNotGreaterThan(String key, long timestamp){
        if(!this.eigerMap.containsKey(key)) return Timestamp.NO_TIMESTAMP;

        Map.Entry<Long,DataItem> res = this.eigerMap.get(key).floorEntry(timestamp);
        
        if(res == null) return Timestamp.NO_TIMESTAMP;
        
        return res.getValue().getTimestamp();
    }

    public long getHighestCommittedPerCid(String key, String cid, long requestedTimestamp){
        if(!this.keyCidVersions.containsKey(new KeyCidPair(key, cid))) return Timestamp.NO_TIMESTAMP;

        Long res =  this.keyCidVersions.get(new KeyCidPair(key, cid));

        if(res == null || requestedTimestamp >= res) return Timestamp.NO_TIMESTAMP;
        
        return res;
    }

    public long getHCT(){
        if(!this.preparedNotCommittedByStamp.isEmpty()) return this.latest_prep;
        return this.latest;
    }

    public Map<String,DataItem> getAllOra(Map<String,DataItem> keyValuePairs, String cid) throws KaijuException{
        Map<String,DataItem> results = Maps.newHashMap();
        long hct = getHCT();
        if(tests){
            for(Map.Entry<String,DataItem> keyPair : keyValuePairs.entrySet()){
                long ts = getHighestCommittedPerCid(keyPair.getKey(), cid, keyPair.getValue().getTimestamp());
                DataItem item;
                if(ts != Timestamp.NO_TIMESTAMP){
                    item = new DataItem(hct, getItemByVersion(keyPair.getKey(), ts).getValue());
                    item.setPrepTs(ts);
                    results.put(keyPair.getKey(), item);
                }else if(keyPair.getValue().getFlag()){
                    KeyTimestampPair kts = new KeyTimestampPair(keyPair.getKey(), keyPair.getValue().getTimestamp());
                    if(!dataItems.containsKey(kts)){
                        item = DataItem.getNullItem();
                        results.put(keyPair.getKey(), item);
                        continue;
                    }
                    item = new DataItem(hct, dataItems.get(kts).getValue());
                    item.setPrepTs(keyPair.getValue().getTimestamp());
                    results.put(keyPair.getKey(), item);
                }else{
                    long hts = getHighestCommittedNotGreaterThan(keyPair.getKey(), keyPair.getValue().getTimestamp(), keyPair.getValue().getPrepTs());
                    item = getItemByVersion(keyPair.getKey(), hts);
                    item.setTimestamp(hct);
                    results.put(keyPair.getKey(), item);
                    item.setPrepTs(hts);
                }
            }
            if(Config.getConfig().freshness_test == 1){
                for(Map.Entry<String,DataItem> entry : results.entrySet()){
                    if(entry.getValue().getPrepTs() == Timestamp.NO_TIMESTAMP) continue;
                    long t = this.freshness_ORA(entry.getKey(), entry.getValue().getPrepTs(), this.latestTime.getOrDefault(entry.getKey(), Timestamp.NO_TIMESTAMP));
                    logger.warn("Freshness for key: " + entry.getKey() + " timestamp: " + entry.getValue().getPrepTs() + " = " + t);
                }
            }
        }else{
            for(Map.Entry<String,DataItem> keyPair : keyValuePairs.entrySet()){
                long ts = getHighestCommittedPerCid(keyPair.getKey(), cid, keyPair.getValue().getTimestamp());
                DataItem item;
                if(ts != Timestamp.NO_TIMESTAMP){
                    item = new DataItem(hct, getItemByVersion(keyPair.getKey(), ts).getValue());
                    results.put(keyPair.getKey(), item);
                }else if(keyPair.getValue().getFlag()){
                    item = new DataItem(hct, dataItems.getOrDefault(new KeyTimestampPair(keyPair.getKey(), keyPair.getValue().getTimestamp()),DataItem.getNullItem()).getValue());
                    results.put(keyPair.getKey(), item);
                }else{
                    long hts = getHighestCommittedNotGreaterThan(keyPair.getKey(), keyPair.getValue().getTimestamp(), keyPair.getValue().getPrepTs());
                    item = getItemByVersion(keyPair.getKey(), hts);
                    item.setTimestamp(hct);
                    results.put(keyPair.getKey(), item);
                }
            }
        }
        return results;
    }

    // get last committed write for each key
    public Map<String, DataItem> getAll(Collection<String> keys) throws KaijuException {
        
        HashMap<String, DataItem> results = Maps.newHashMap();

        for(String key : keys) {
            DataItem item = getLatestItemForKey(key);

            if(item == null)
                item = DataItem.getNullItem();

            results.put(key, item);
        }

        return results;
    }

    // get last commited timestamp for set of keys
    public Collection<Long> getTimestamps(Collection<String> keys) throws KaijuException {
        Collection<Long> results = Lists.newArrayList();

        for(String key : keys) {
            if(lastCommitForKey.containsKey(key))
                results.add(lastCommitForKey.get(key));
        }

        return results;
    }

    // probably could have passed a map, in retrospect
    public Map<String, DataItem> getAllByVersion(Collection<ItemVersion> versions) throws KaijuException {
        HashMap<String, DataItem> results = Maps.newHashMap();
        if(MemoryStorageEngine.is_NOC()){
            for(ItemVersion version : versions) {
                results.put(version.getKey(), getByTimestamp(version.getKey(), getHighestCommittedNotGreaterThan(version.getKey(), version.getTimestamp())));
            }
            return results;
        }
        for(ItemVersion version : versions) {
            results.put(version.getKey(), getByTimestamp(version.getKey(), version.getTimestamp()));
        }

        return results;
    }

    // find the highest timestamped version of each key in keys that is present in versions (RAMP-Small)
    public Map<String, DataItem> getAllByVersionList(Collection<String> keys,
                                                     Collection<Long> versions) throws KaijuException {
        HashMap<String, DataItem> results = Maps.newHashMap();

        List<Long> timestampList = Ordering.natural().reverse().sortedCopy(versions);

        for(String key : keys) {
            DataItem item = getByTimestampList(key, timestampList);

            if(item == null)
                item = DataItem.getNullItem();

            results.put(key, item);
        }

        return results;
    }

    // used in RAMP-Hybrid; allow false positives
    public Map<String, DataItem> getEachByVersionList(Map<String, Collection<Long>> keyVersions) throws KaijuException {
        HashMap<String, DataItem> results = Maps.newHashMap();

        for(String key : keyVersions.keySet()) {
            Collection<Long> versions = keyVersions.get(key);
            List<Long> timestampList = Ordering.natural().reverse().sortedCopy(versions);

            DataItem item = getByTimestampList(key, timestampList);

            if(item == null)
                item = DataItem.getNullItem();

            results.put(key, item);
        }

        return results;
    }

    public DataItem get(String key) {
        return getLatestItemForKey(key);
    }

    private DataItem getByTimestamp(String key, Long requiredTimestamp) throws KaijuException {
        assert(requiredTimestamp != Timestamp.NO_TIMESTAMP);

        DataItem ret = getItemByVersion(key, requiredTimestamp);
        if(ret == null){
            ret = DataItem.getNullItem();
            //ret = getHighestNotGreaterThan(key, requiredTimestamp);
        }

        if(ret == null)
            logger.warn("No suitable value found for key " + key
                                               + " version " + requiredTimestamp);
        else if(Config.getConfig().freshness_test == 1 && ret.getTimestamp() != Timestamp.NO_TIMESTAMP){
            long t = this.freshness(key, requiredTimestamp);
            logger.warn("Round 2 Freshness for key: " + key + " timestamp: " + requiredTimestamp + " = " + t);
        }
        return ret;
    }

    // return the highest found timestamp that matches the list
    // assumes that inputTimestampList is sorted
    private DataItem getByTimestampList(String key, List<Long> inputTimestampList) throws KaijuException {
        // have to examine pending items now; look from highest to lowest
        for(long candidateStamp : inputTimestampList) {
            DataItem candidate = getItemByVersion(key, candidateStamp);
            if(candidate != null && Config.getConfig().freshness_test == 1){
                long t = this.freshness(key, candidateStamp);
                logger.warn("Freshness for key: " + key + " timestamp: " + candidateStamp + " = " + t);
                return candidate;
            }
        }

        return null;
    }

    private DataItem getLatestItemForKey(String key) {
        if(!lastCommitForKey.containsKey(key))
            return DataItem.getNullItem();
        if(Config.getConfig().freshness_test == 1){
            long t = this.freshness(key, lastCommitForKey.get(key));
            logger.warn("Round 1 Freshness for key: " + key + " timestamp: " + lastCommitForKey.get(key) + " = " + t);
        }
        return getItemByVersion(key, lastCommitForKey.get(key));
    }

    private DataItem getItemByVersion(String key, long timestamp) {
        return dataItems.getOrDefault(new KeyTimestampPair(key,  timestamp),DataItem.getNullItem());
    }

    public void putAll(Map<String, DataItem> pairs) throws KaijuException {
        assert(!pairs.isEmpty());

        for(Map.Entry<String, DataItem> pair : pairs.entrySet()) {
            put(pair.getKey(), pair.getValue());
        }
    }

    public void put(String key, DataItem value) throws KaijuException {
        prepare(key, value);
        commit(key, value.getTimestamp());
    }
    private void commit(String key, Long timestamp) throws KaijuException {
        // put if newer
        if(!dataItems.containsKey(new KeyTimestampPair(key,timestamp))) return;
        if(MemoryStorageEngine.is_ORA()) {
            if(!eigerMap.containsKey(key))
                eigerMap.putIfAbsent(key, new ConcurrentSkipListMap<Long, DataItem>());
            eigerMap.get(key).put(timestamp, dataItems.get(new KeyTimestampPair(key, timestamp)));
            String cid = getItemByVersion(key, timestamp).getCid();
            KeyCidPair pair = new KeyCidPair(key, cid);
            if(!keyCidVersions.containsKey(pair) || keyCidVersions.get(pair) < timestamp){
                keyCidVersions.put(pair, timestamp);
            }
        }else if(MemoryStorageEngine.is_NOC()){
            if(!eigerMap.containsKey(key))
                eigerMap.putIfAbsent(key, new ConcurrentSkipListMap<Long, DataItem>());
            eigerMap.get(key).put(timestamp, dataItems.get(new KeyTimestampPair(key, timestamp)));
        }

        while(true) {
            Long oldCommitted = lastCommitForKey.get(key);
            if(oldCommitted == null) {
                if(lastCommitForKey.putIfAbsent(key, timestamp) == null) {
                    break;
                }
            } else if(oldCommitted < timestamp) {
                if(lastCommitForKey.replace(key, oldCommitted, timestamp)) {
                    markForGC(key, oldCommitted);
                    overwrittenMeter.mark();
                    break;
                }
            } else {
                markForGC(key, timestamp);
                nopWriteMeter.mark();
                break;
            }
        }
        if(Config.getConfig().replication == 1){
            this.toReplicate.add(new KeyTimestampPair(key, timestamp));
        }
    }

    public void prepare(Map<String, DataItem> pairs) throws KaijuException {
        if(pairs.isEmpty()) {
            logger.warn("prepare of zero key value pairs?");
            return;
        }
        // all pairs will have the same timestamp, but we still send the
        // pairs with separate timestamps because they'll be stored that way
        long timestamp = pairs.values().iterator().next().getTimestamp();
        
        if(abortedTxns.containsKey(timestamp)) {
            throw new AbortedException("Timestamp was already aborted pre-commit "+timestamp);
        }

        

        List<KeyTimestampPair> pendingPairs = Lists.newArrayList();

        for(Map.Entry<String, DataItem> pair : pairs.entrySet()) {
            prepare(pair.getKey(), pair.getValue());
            pendingPairs.add(new KeyTimestampPair(pair.getKey(), pair.getValue().getTimestamp()));
        }
        preparedNotCommittedByStamp.put(timestamp, pendingPairs);
        
        if(MemoryStorageEngine.is_ORA() || MemoryStorageEngine.is_NOC()){
            this.prep.add(timestamp);
            Long lat = this.prep.first();
            latest_prep = lat;
        }
    }

    public void commit(long timestamp) throws KaijuException {
        if((!preparedNotCommittedByStamp.containsKey(timestamp))) return;
        
        List<KeyTimestampPair> toUpdate = preparedNotCommittedByStamp.get(timestamp);
        
        if(toUpdate == null) {
            return;
        }
        
        for(KeyTimestampPair pair : toUpdate) {
            commit(pair.getKey(), pair.getTimestamp());
        }
        preparedNotCommittedByStamp.remove(timestamp);
        if(MemoryStorageEngine.is_ORA() || MemoryStorageEngine.is_NOC()){
            this.prep.remove(timestamp);
            if(!this.prep.isEmpty()) this.latest_prep = this.prep.first();
            else this.latest_prep = Timestamp.NO_TIMESTAMP;
            if(timestamp > latest) latest = timestamp;
        }
        if(Config.getConfig().freshness_test == 1){
            long time = System.currentTimeMillis();
            for(KeyTimestampPair pair : toUpdate){
                String key = pair.key;
                this.timesPerVersion.putIfAbsent(this.createNewKeyTimestampPair(key, timestamp),time);
                if(!this.latestTime.containsKey(key) || this.latestTime.get(key) < time){
                    this.latestTime.put(key, time);
                }
            }
        }
        
    }

    private void prepare(String key, DataItem value) {
        dataItems.put(new KeyTimestampPair(key, value.getTimestamp()), value);
        if(Config.getConfig().freshness_test != 1) // freshness logging and measurement slows the system to the point that GC for these is not useful.
            markPreparedForGC(key,value.getTimestamp()); 
        if(isEiger) {
            if(!eigerMap.containsKey(key))
                eigerMap.putIfAbsent(key, new ConcurrentSkipListMap<Long, DataItem>());
            eigerMap.get(key).put(value.getTimestamp(), value);
        }
    }

    public DataItem getHighestNotGreaterThan(String key, long timestamp) {
        //assert(isEiger);

        ConcurrentSkipListMap<Long, DataItem> skipListMap = eigerMap.get(key);
        if(skipListMap == null)
            return DataItem.getNullItem();

        Map.Entry<Long, DataItem> ret = skipListMap.floorEntry(timestamp);
        if(ret == null)
            return DataItem.getNullItem();

        return ret.getValue();
    }

    public boolean isPreparedOrHigherCommitted(String item, long timestamp) {
        if(abortedTxns.containsKey(timestamp)) {
            return false;
        }

        Long latestItem = lastCommitForKey.get(item);
        if(latestItem != null && latestItem >= timestamp) {
            return true;
        }

        if(preparedNotCommittedByStamp.containsKey(timestamp)) {
            return true;
        }

        latestItem = lastCommitForKey.get(item);
        if(latestItem != null && latestItem >= timestamp) {
            return true;
        }

        abortedTxns.put(timestamp, Boolean.TRUE);
        return false;
    }

    private void markForGC(String key, long timestamp) {
        if(Config.getConfig().freshness_test == 1) return;
        KeyTimestampPair stamp = new KeyTimestampPair(key,
                                                      timestamp,
                                                      System.currentTimeMillis()+gcTimeMs);
        candidatesForGarbageCollection.add(stamp);
    }

    private void markPreparedForGC(String key, long timestamp){
        KeyTimestampPair stamp = new KeyTimestampPair(key,
                                                      timestamp,
                                                      System.currentTimeMillis()+gcTimePrepMs);
        candidatesForGarbageCollection.add(stamp);
    }

    public KeyTimestampPair createNewKeyTimestampPair(String key, long timestamp){
        return new KeyTimestampPair(key,timestamp);
    }

    public class KeyTimestampPair {
        private String key;
        private long timestamp;
        private long expirationTime = -1;

        public KeyTimestampPair(String key, long timestamp) {
            this.key = key;
            this.timestamp = timestamp;
        }

        public KeyTimestampPair(String key, long timestamp, long expirationTime) {
            this(key, timestamp);
            this.expirationTime = expirationTime;
        }

        public long getExpirationTime(){
            return expirationTime;
        }

        public String getKey() {
            return key;
        }

        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public int hashCode() {
            return key.hashCode()*Long.valueOf(timestamp).hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null)
                 return false;
             if (obj == this)
                 return true;
             if (!(obj instanceof KeyTimestampPair))
                 return false;

             KeyTimestampPair rhs = (KeyTimestampPair) obj;
             return rhs.getTimestamp() == timestamp && rhs.getKey().equals(key);
        }
    }

    public Iterable<Long> getPendingStamps() {
        return preparedNotCommittedByStamp.keySet();
    }

    public Collection<String> getPendingKeys(long timestamp) throws KaijuException {
        List<KeyTimestampPair> pairs = preparedNotCommittedByStamp.get(timestamp);

        if(pairs == null || pairs.isEmpty()) {
            return null;
        }

        KeyTimestampPair pair = pairs.iterator().next();
        return getByTimestamp(pair.key, pair.timestamp).getTransactionKeys();
    }

    // only used in CTP
    public void abort(long timestamp) {
        abortedTxns.put(timestamp, Boolean.TRUE);
        List<KeyTimestampPair> pairs = preparedNotCommittedByStamp.remove(timestamp);

        if(pairs != null) {
            for(KeyTimestampPair pair : pairs) {
                dataItems.remove(pair);
            }
        }
    }

    public void reset() {
        preparedNotCommittedByStamp.clear();
        dataItems.clear();
        eigerMap.clear();
        lastCommitForKey.clear();
        candidatesForGarbageCollection.clear();
        isEiger = Config.getConfig().isolation_level == IsolationLevel.EIGER;
    }

    public static boolean is_ORA(){
        return Config.getConfig().readatomic_algorithm == ReadAtomicAlgorithm.CONST_ORT;
    }

    public static boolean is_NOC(){
        return Config.getConfig().readatomic_algorithm == ReadAtomicAlgorithm.NOC;
    }
}