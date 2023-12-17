package edu.berkeley.kaiju.service.request.handler;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import edu.berkeley.kaiju.KaijuServer;
import edu.berkeley.kaiju.config.Config;
import edu.berkeley.kaiju.data.DataItem;

import edu.berkeley.kaiju.exception.HandlerException;
import edu.berkeley.kaiju.net.routing.OutboundRouter;
import edu.berkeley.kaiju.service.request.RequestDispatcher;
import edu.berkeley.kaiju.service.request.message.KaijuMessage;
import edu.berkeley.kaiju.service.request.message.request.*;
import edu.berkeley.kaiju.service.request.message.response.KaijuResponse;
import edu.berkeley.kaiju.util.Timestamp;


import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class ReadAtomicLoraBasedKaijuServiceHandler extends ReadAtomicKaijuServiceHandler {

    public ReadAtomicLoraBasedKaijuServiceHandler(RequestDispatcher dispatcher){
        super(dispatcher);
    }

    public boolean canAdd(String key, long timestamp){
        return (!KaijuServer.last.containsKey(key) || KaijuServer.last.get(key) < timestamp);
    }

    private long getLastTimestamp(String key){
        return KaijuServer.last.getOrDefault(key, Timestamp.NO_TIMESTAMP);
    }

    public void prepare_all(Map<String, byte[]> keyValuePairs, long timestamp) throws HandlerException{
        try {
            // generate a timestamp for this transaction
            // group keys by responsible server.
            Map<Integer, Collection<String>> keysByServerID = OutboundRouter.getRouter().groupKeysByServerID(keyValuePairs.keySet());
            Map<Integer, KaijuMessage> requestsByServerID = Maps.newHashMap();
            synchronized(this){
                for(int serverID : keysByServerID.keySet()) {
                    Map<String, DataItem> keyValuePairsForServer = Maps.newHashMap();
                    for(String key : keysByServerID.get(serverID)) {
                        keyValuePairsForServer.put(key, instantiateKaijuItem(keyValuePairs.get(key),
                                                                            keyValuePairs.keySet(),
                                                                            timestamp));
                        if(canAdd(key, timestamp)) KaijuServer.last.put(key, timestamp);
                    }
                    requestsByServerID.put(serverID, new PreparePutAllRequest(keyValuePairsForServer));
                }
            }
            // execute the prepare phase and check for errors
            Collection<KaijuResponse> responses = dispatcher.multiRequest(requestsByServerID);
            KaijuResponse.coalesceErrorsIntoException(responses);
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
            if(Config.getConfig().ra_tester == 1){
                for(String key : keyValuePairs.keySet()){
                    KaijuServiceHandler.logger.warn("TR: w(" + key + "," + ((Long)timestamp).toString() + "," + this.cid.get() + "," + this.tid.get() + ")");
                }
            }
        }catch(Exception e){
            throw new HandlerException("Error processing request",e);
        }
    }

    public Map<String,byte[]> get_all(List<String> keys) throws HandlerException{
        try{
            Map<String,Long> keyPairs = Maps.newHashMap();
                            
            keys.forEach(k -> {
                long ts = getLastTimestamp(k);
                if(ts != Timestamp.NO_TIMESTAMP)
                    keyPairs.put(k, getLastTimestamp(k));
            });

            Collection<KaijuResponse> responses = fetch_by_version_from_server(keyPairs);
            Map<String,byte[]> keyValuePairs = new HashMap<String,byte[]>();
            
            for(KaijuResponse response : responses){
                for(Map.Entry<String,DataItem> entry : response.keyValuePairs.entrySet()){
                    if(entry == null || entry.getValue() == null || entry.getValue().getValue() == null) continue;
                    keyValuePairs.put(entry.getKey(), entry.getValue().getValue());
                    if(entry.getValue().getTransactionKeys() == null) continue;
                    synchronized(this){
                    if(canAdd(entry.getKey(), entry.getValue().getTimestamp())) KaijuServer.last.put(entry.getKey(), entry.getValue().getTimestamp());
                        for(String key : entry.getValue().getTransactionKeys()){
                            if(canAdd(key, entry.getValue().getTimestamp())){
                                KaijuServer.last.put(key, entry.getValue().getTimestamp());
                            }
                        }
                    }
                }
            }
            if(Config.getConfig().ra_tester == 1){
                for(Map.Entry<String,Long> entry : keyPairs.entrySet())
                    KaijuServiceHandler.logger.warn("TR: r(" + entry.getKey() + "," + ((Long)entry.getValue()).toString() + "," + cid.get() + "," + ((Long)tid.get()).toString() + ")");
            }
            return keyValuePairs;
        }catch(Exception e){
            throw new HandlerException("Error processing request",e);
        }
    }
    
    public DataItem instantiateKaijuItem(byte[] value,
                                        Collection<String> allKeys,
                                        long timestamp) {
        return new DataItem(timestamp, value, Lists.newArrayList(allKeys));
    }
}
