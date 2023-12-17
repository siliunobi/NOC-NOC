package edu.berkeley.kaiju.service.request.handler;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListSet;

import com.beust.jcommander.internal.Maps;

import edu.berkeley.kaiju.KaijuServer;
import edu.berkeley.kaiju.data.DataItem;
import edu.berkeley.kaiju.exception.HandlerException;
import edu.berkeley.kaiju.net.routing.OutboundRouter;
import edu.berkeley.kaiju.service.request.RequestDispatcher;
import edu.berkeley.kaiju.service.request.message.KaijuMessage;
import edu.berkeley.kaiju.service.request.message.request.CommitPutAllRequest;
import edu.berkeley.kaiju.service.request.message.request.PreparePutAllRequest;
import edu.berkeley.kaiju.service.request.message.response.KaijuResponse;
import edu.berkeley.kaiju.util.Timestamp;

public class ReadAtomicNOCBasedKaijuServiceHandler extends ReadAtomicKaijuServiceHandler{

    public ReadAtomicNOCBasedKaijuServiceHandler(RequestDispatcher dispatcher) {
        super(dispatcher);
    }
    
    private void addHct(int serverId, long hct){
        if(!KaijuServer.hcts.containsKey(serverId) || KaijuServer.hcts.get(serverId) < hct){
            KaijuServer.hcts.put(serverId, hct);
        }
    }

    @Override
    public void put_all(Map<String, byte[]> keyValuePairs) throws HandlerException {
        try{
            Long timestamp = Timestamp.assignNewTimestamp();
            Map<Integer, Collection<String>> keysByServerID = OutboundRouter.getRouter().groupKeysByServerID(keyValuePairs.keySet());
                Map<Integer, KaijuMessage> requestsByServerID = Maps.newHashMap();

                for(int serverID : keysByServerID.keySet()) {
                    Map<String, DataItem> keyValuePairsForServer = Maps.newHashMap();
                    for(String key : keysByServerID.get(serverID)) {
                        keyValuePairsForServer.put(key, instantiateKaijuItem(keyValuePairs.get(key),
                                                                            keyValuePairs.keySet(),
                                                                            timestamp));
                    }
                    requestsByServerID.put(serverID, new PreparePutAllRequest(keyValuePairsForServer));
                }

                // execute the prepare phase and check for errors
                Collection<KaijuResponse> responses = dispatcher.multiRequest(requestsByServerID);
                KaijuResponse.coalesceErrorsIntoException(responses);
                for(KaijuResponse response : responses){
                    addHct(response.senderID, response.getHct());
                }
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
                responses = dispatcher.multiRequest(requestsByServerID);
                KaijuResponse.coalesceErrorsIntoException(responses);

                while(true){
                    ConcurrentSkipListSet<Long> set = new ConcurrentSkipListSet<>(KaijuServer.hcts.values());
                    long min = set.first();
                    if(min >= timestamp) break;
                    else Thread.sleep(1);
                    // REMARK: here the thread sleeps for 10ms, this can be tune but is in general needed for performance, 
                    // else the blocked write threads will slow done the concurrent reads needlessy
                }
                
                for(KaijuResponse response : responses){
                    addHct(response.senderID,response.getHct());
                }
            } catch (Exception e) {
                throw new HandlerException("Error processing request", e);
            }
        return;
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

    @Override
    public Map<String, byte[]> get_all(List<String> keys) throws HandlerException {
        try{
            Map<Integer, Collection<String>> keysByServerID = OutboundRouter.getRouter().groupKeysByServerID(keys);
            long requestedTimestamp = getRequestedTimestamp(keysByServerID.keySet());
            Map<String,Long> keyPairs = Maps.newHashMap();
            keys.forEach(k ->{
                keyPairs.put(k,requestedTimestamp);
            });
            Collection<KaijuResponse> responses = fetch_by_version_from_server(keyPairs);
            Map<String,byte[]> result = new HashMap<String,byte[]>();
            for(KaijuResponse response : responses){
                long hct = response.getHct();
                addHct(response.senderID, hct);
                for(Map.Entry<String,DataItem> keyPair : response.keyValuePairs.entrySet()){
                    if(keyPair != null && keyPair.getValue() != null && keyPair.getValue().getValue() != null)
                        result.put(keyPair.getKey(), keyPair.getValue().getValue());
                }
            }
            return result;
        }catch(Exception e){
            throw new HandlerException("Error processing request",e);
        }
    }


    @Override
    public DataItem instantiateKaijuItem(byte[] value, Collection<String> allKeys, long timestamp) {
        DataItem item =  new DataItem(timestamp, value);
        return item;
    }
    
}
