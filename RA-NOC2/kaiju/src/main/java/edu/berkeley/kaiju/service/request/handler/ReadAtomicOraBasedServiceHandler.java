package edu.berkeley.kaiju.service.request.handler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.beust.jcommander.internal.Maps;

import edu.berkeley.kaiju.KaijuServer;
import edu.berkeley.kaiju.config.Config;
import edu.berkeley.kaiju.data.DataItem;
import edu.berkeley.kaiju.exception.HandlerException;
import edu.berkeley.kaiju.net.routing.OutboundRouter;
import edu.berkeley.kaiju.service.request.RequestDispatcher;
import edu.berkeley.kaiju.service.request.message.KaijuMessage;
import edu.berkeley.kaiju.service.request.message.request.CommitPutAllRequest;
import edu.berkeley.kaiju.service.request.message.request.PreparePutAllRequest;
import edu.berkeley.kaiju.service.request.message.request.PutAllRequest;
import edu.berkeley.kaiju.service.request.message.response.KaijuResponse;
import edu.berkeley.kaiju.util.Timestamp;

public class ReadAtomicOraBasedServiceHandler extends ReadAtomicKaijuServiceHandler{

    public ReadAtomicOraBasedServiceHandler(RequestDispatcher dispatcher) {
        super(dispatcher);
    }

    public void addPrep(Collection<String> keys, long timestamp){
        synchronized(this){
            for(String key : keys){
                if(!KaijuServer.prep.containsKey(key) || KaijuServer.prep.get(key) < timestamp)
                    KaijuServer.prep.put(key, timestamp);
            }
        }
    }

    public void removePrep(Collection<String> keys,long timestamp){
        synchronized(this){
            for(String key:keys){
                if(KaijuServer.prep.containsKey(key) && KaijuServer.prep.get(key) == timestamp) KaijuServer.prep.remove(key);
            }
        }
    }

    private long getRequestedTimestamp(Collection<Integer> servers){
        Long min = Timestamp.NO_TIMESTAMP;
        for(int server : servers){
            Long ts = KaijuServer.hcts.getOrDefault(server, Timestamp.NO_TIMESTAMP);
            if(min == Timestamp.NO_TIMESTAMP || ts < min){
                min = ts;
            }
        }
        return min;
    }

    
    private void addHct(int serverId, long hct){
        if(!KaijuServer.hcts.containsKey(serverId) || KaijuServer.hcts.get(serverId) < hct){
            KaijuServer.hcts.put(serverId, hct);
        }
    }

    @Override
    public Map<String, byte[]> get_all(List<String> keys) throws HandlerException {
        try{
            Map<Integer, Collection<String>> keysByServerID = Maps.newHashMap();
            long requestedTimestamp = Long.MAX_VALUE;
            for(String key : keys) {
                int serverID = OutboundRouter.getRouter().getServerIDByResourceID(key.hashCode());
                if(!keysByServerID.containsKey(serverID)){
                    keysByServerID.put(serverID,  new ArrayList<String>());
                    long ts = KaijuServer.hcts.getOrDefault(serverID,Long.MAX_VALUE);
                    if(ts < requestedTimestamp){
                        requestedTimestamp = ts;
                    }
                }
                keysByServerID.get(serverID).add(key);
            }
            if(requestedTimestamp == Long.MAX_VALUE) requestedTimestamp = Timestamp.NO_TIMESTAMP;
            Map<Integer, KaijuMessage> requestsByServerID = Maps.newHashMap();

            for(int serverID : keysByServerID.keySet()) {
                Map<String, DataItem> keyValuePairsForServer = Maps.newHashMap();
                for(String key : keysByServerID.get(serverID)) {
                    DataItem item = new DataItem();
                    item.setTimestamp(requestedTimestamp);
                    long prepTimestamp = KaijuServer.prep.getOrDefault(key,Timestamp.NO_TIMESTAMP);
                    if(prepTimestamp == Timestamp.NO_TIMESTAMP){
                        keyValuePairsForServer.put(key, item);
                        continue;
                    }
                    if(requestedTimestamp < prepTimestamp){
                        item.setFlag(true);
                        item.setTimestamp(prepTimestamp);
                    }else{
                        item.setPrepTs(prepTimestamp);
                    }

                    keyValuePairsForServer.put(key, item);
                }
        
                requestsByServerID.put(serverID, new PutAllRequest(keyValuePairsForServer));
            }
            Collection<KaijuResponse> responses = dispatcher.multiRequest(requestsByServerID);
            KaijuResponse.coalesceErrorsIntoException(responses);

            Map<String,byte[]> result = Maps.newHashMap();
            for(KaijuResponse response : responses){
                long hct = response.keyValuePairs.values().iterator().next().getTimestamp();
                addHct(response.senderID, hct);
                for(Map.Entry<String,DataItem> keyPair : response.keyValuePairs.entrySet()){
                    result.put(keyPair.getKey(), keyPair.getValue().getValue());
                }
            }
            if(Config.getConfig().ra_tester == 1){
                tester_read(responses);
            }
            return result;
        }catch(Exception e){
            throw new HandlerException("Error processing request",e);
        }
    }

    private void tester_read(Collection<KaijuResponse> responses){
        for(KaijuResponse response : responses){
            for(Map.Entry<String,DataItem> keyValuePair : response.keyValuePairs.entrySet()){
                if(keyValuePair != null && keyValuePair.getValue() != null && keyValuePair.getValue().getPrepTs() != Timestamp.NO_TIMESTAMP)
                    KaijuServiceHandler.logger.warn("TR: r(" + keyValuePair.getKey() + "," + ((Long)keyValuePair.getValue().getPrepTs()).toString() + "," + cid.get() + "," + tid.get() + ")");
            }
        }
    }


    public void prepare_all(Map<String, byte[]> keyValuePairs, long timestamp) throws HandlerException{
        try {
            // generate a timestamp for this transaction
            // group keys by responsible server.
            Map<Integer, Collection<String>> keysByServerID = OutboundRouter.getRouter().groupKeysByServerID(keyValuePairs.keySet());
            Map<Integer, KaijuMessage> requestsByServerID = Maps.newHashMap();

            for(int serverID : keysByServerID.keySet()) {
                Map<String, DataItem> keyValuePairsForServer = Maps.newHashMap();
                for(String key : keysByServerID.get(serverID)) {
                    keyValuePairsForServer.put(key, instantiateKaijuItem(keyValuePairs.get(key),
                                                                         keyValuePairs.keySet(),
                                                                         timestamp));
                    keyValuePairsForServer.get(key).setCid(Config.getConfig().server_id.toString());
                }

                requestsByServerID.put(serverID, new PreparePutAllRequest(keyValuePairsForServer));
            }

            // execute the prepare phase and check for errors
            Collection<KaijuResponse> responses = dispatcher.multiRequest(requestsByServerID);
            KaijuResponse.coalesceErrorsIntoException(responses);

            synchronized(this){
                for(KaijuResponse response : responses){
                    addHct(response.senderID,response.getHct());
                    for(String key: keysByServerID.get(Short.toUnsignedInt(response.senderID))){
                        if(!KaijuServer.prep.containsKey(key) || KaijuServer.prep.get(key) < timestamp)
                        KaijuServer.prep.put(key, timestamp);
                    }
                }
             }

            if(Config.getConfig().ra_tester == 1){
                for(String key : keyValuePairs.keySet()){
                    KaijuServiceHandler.logger.warn("TR: w(" + key + "," + ((Long)timestamp).toString() + "," + cid.get() + "," + tid.get() + ")");
                }
            }
        } catch (Exception e) {
            throw new HandlerException("Error processing request", e);
        }
    }

    public void commit_all(Map<String, byte[]> keyValuePairs,long timestamp) throws HandlerException{
        try{
            Map<Integer, Collection<String>> keysByServerID = OutboundRouter.getRouter().groupKeysByServerID(keyValuePairs.keySet());
            Map<Integer, KaijuMessage> requestsByServerID = Maps.newHashMap();
            for(int serverID : keysByServerID.keySet()) {
                requestsByServerID.put(serverID,  new CommitPutAllRequest(timestamp));
            }

            // this is only for the experiment in Section 5.3 and will trigger CTP
            if(dropCommitPercentage != 0 && random.nextFloat() < dropCommitPercentage) {
                int size = keysByServerID.size();
                int item = random.nextInt(size);
                int i = 0;
                for(int serverID : keysByServerID.keySet())
                {
                    if (i == item) {
                        requestsByServerID.remove(serverID);
                        break;
                    }

                    i++;
                }
            }
            if(requestsByServerID.isEmpty()) {
                return;
            }
            Collection<KaijuResponse> responses = dispatcher.multiRequest(requestsByServerID);
            KaijuResponse.coalesceErrorsIntoException(responses);
            for(KaijuResponse response : responses){
                addHct(response.senderID,response.getHct());
            }
            removePrep(keyValuePairs.keySet(), timestamp);
            
            for(int server : requestsByServerID.keySet()){
                 addHct(server, timestamp);
            }
        }catch(Exception e){
            throw new HandlerException("Error processing request",e);
        }
    }

    @Override
    public void put_all(Map<String, byte[]> keyValuePairs) throws HandlerException {
        Long timestamp = Timestamp.assignNewTimestamp();
        prepare_all(keyValuePairs, timestamp);
        commit_all(keyValuePairs, timestamp);
        return;
    }
    
    @Override
    public DataItem instantiateKaijuItem(byte[] value, Collection<String> allKeys, long timestamp) {
        
        DataItem item =  new DataItem(timestamp, value);
        return item;
    }
    
}
