package edu.berkeley.kaiju.service.request.handler;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import edu.berkeley.kaiju.KaijuServer;
import edu.berkeley.kaiju.config.Config;
import edu.berkeley.kaiju.data.DataItem;
import edu.berkeley.kaiju.exception.HandlerException;
import edu.berkeley.kaiju.net.routing.OutboundRouter;
import edu.berkeley.kaiju.service.request.RequestDispatcher;
import edu.berkeley.kaiju.service.request.message.KaijuMessage;
import edu.berkeley.kaiju.service.request.message.request.EigerPutAllRequest;
import edu.berkeley.kaiju.service.request.message.response.KaijuResponse;
import edu.berkeley.kaiju.util.Timestamp;

public class EigerPortPlusKaijuServiceHandler implements IKaijuHandler{
    RequestDispatcher dispatcher;
    
    public EigerPortPlusKaijuServiceHandler(RequestDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    Random random = new Random();

    @Override
    public Map<String, byte[]> get_all(List<String> keys) throws HandlerException {
        try {
            long readStamp = Timestamp.assignNewTimestamp();
            long gst = get_read_ts();

            Map<Integer, Collection<String>> keysByServerID = OutboundRouter.getRouter().groupKeysByServerID(keys);
            Map<Integer, KaijuMessage> requestsByServerID = Maps.newHashMap();
            String cid;
            if(Config.getConfig().threadLocal == 1){
                cid = Config.getConfig().server_id.toString() + ":" + Long.valueOf(Thread.currentThread().getId()).toString();
            }else{
                cid = Config.getConfig().server_id.toString(); //+ ":" + Long.valueOf(Thread.currentThread().getId()).toString();
            }
            for(int serverID : keysByServerID.keySet()) {
                Map<String,DataItem> keysWithMd = new HashMap<String,DataItem>();
                for(String key : keysByServerID.get(serverID)){
                    keysWithMd.put(key, new DataItem(gst,new byte[0]));
                    keysWithMd.get(key).setCid(cid);
                }
                requestsByServerID.put(serverID, new EigerPutAllRequest(keysWithMd, readStamp));
            }

            Collection<KaijuResponse> responses = dispatcher.multiRequest(requestsByServerID);

            Map<String, byte[]> ret = Maps.newHashMap();

            KaijuResponse.coalesceErrorsIntoException(responses);
            synchronized(this){
                for(KaijuResponse response : responses) {
                    for(Map.Entry<String, DataItem> keyValuePair : response.keyValuePairs.entrySet()) {
                        ret.put(keyValuePair.getKey(), keyValuePair.getValue().getValue());
                    }
                    if(Config.getConfig().threadLocal == 1){
                        if(!KaijuServer.hctsLocal.get().containsKey(Integer.valueOf(response.senderID)) || KaijuServer.hctsLocal.get().get(Integer.valueOf(response.senderID)) < response.getHct()){
                            KaijuServer.hctsLocal.get().put(Integer.valueOf(response.senderID),response.getHct());
                        }
                    }
                    if(!KaijuServer.hcts.containsKey(Integer.valueOf(response.senderID)) || KaijuServer.hcts.get(Integer.valueOf(response.senderID)) < response.getHct()){
                        KaijuServer.hcts.put(Integer.valueOf(response.senderID),response.getHct());
                    }
                }
            }
            return ret;
        } catch (Exception e) {
            throw new HandlerException("Error processing request", e);
        }
    }

    @Override
    public void put_all(Map<String, byte[]> keyValuePairs) throws HandlerException {
        try {
            List<String> keys = Lists.newArrayList(keyValuePairs.keySet());
            String coordinatorKey = keys.get(random.nextInt(keys.size()));
            Long gst;
            if(Config.getConfig().threadLocal == 1){
                gst = KaijuServer.gstLocal.get();
            }else{
                gst = KaijuServer.gst;
            }
            Map<Integer, Collection<String>> keysByServerID = OutboundRouter.getRouter().groupKeysByServerID(
                    keyValuePairs.keySet());
            Map<Integer, KaijuMessage> requestsByServerID = Maps.newHashMap();

            long timestamp = Timestamp.assignNewTimestamp();
            String cid;
            if(Config.getConfig().threadLocal == 1){
                cid = Config.getConfig().server_id.toString() + ":" + Long.valueOf(Thread.currentThread().getId()).toString();
            }else{
                cid = Config.getConfig().server_id.toString(); //+ ":" + Long.valueOf(Thread.currentThread().getId()).toString();
            }
            for(int serverID : keysByServerID.keySet()) {
                Map<String, DataItem> keyValuePairsForServer = Maps.newHashMap();
                for(String key : keysByServerID.get(serverID)) {
                    keyValuePairsForServer.put(key, new DataItem(timestamp, keyValuePairs.get(key)));
                    keyValuePairsForServer.get(key).setCid(cid);
                    keyValuePairsForServer.get(key).setPrepTs(gst);
                }

                requestsByServerID.put(serverID, new EigerPutAllRequest(keyValuePairsForServer,
                                                                        coordinatorKey,
                                                                        keyValuePairs.size()));
            }

            Collection<KaijuResponse> responses = dispatcher.multiRequestBlockFor(requestsByServerID,keysByServerID.keySet().size());

            KaijuResponse.coalesceErrorsIntoException(responses);
            if(Config.getConfig().threadLocal == 1){
                for(KaijuResponse response : responses){
                    if(!KaijuServer.hctsLocal.get().containsKey(Integer.valueOf(response.senderID)) || KaijuServer.hctsLocal.get().get(Integer.valueOf(response.senderID)) < response.getHct()){
                        KaijuServer.hctsLocal.get().put(Integer.valueOf(response.senderID),response.getHct());
                    }
                }
                return;
            }
            synchronized(this){
                for(KaijuResponse response : responses){
                    if(!KaijuServer.hcts.containsKey(Integer.valueOf(response.senderID)) || KaijuServer.hcts.get(Integer.valueOf(response.senderID)) < response.getHct()){
                        KaijuServer.hcts.put(Integer.valueOf(response.senderID),response.getHct());
                    }
                }
            }

        } catch (Exception e) {
            throw new HandlerException("Error processing request", e);
        }
    }
    
    private Long get_read_ts(){
        if(Config.getConfig().threadLocal == 1){
            Long min_ts = Timestamp.NO_TIMESTAMP;
            for(Long ts : KaijuServer.hctsLocal.get().values()){
                if(min_ts == Timestamp.NO_TIMESTAMP || ts < min_ts) min_ts = ts;
            }
            if(KaijuServer.gstLocal.get() < min_ts) KaijuServer.gstLocal.set(min_ts);
            return KaijuServer.gstLocal.get();
        }
        Long min_ts = Timestamp.NO_TIMESTAMP;
        for(Long ts : KaijuServer.hcts.values()){
            if(min_ts == Timestamp.NO_TIMESTAMP || ts < min_ts) min_ts = ts;
        }
        if(KaijuServer.gst < min_ts) KaijuServer.gst = min_ts;
        return KaijuServer.gst;
    }
       
}
