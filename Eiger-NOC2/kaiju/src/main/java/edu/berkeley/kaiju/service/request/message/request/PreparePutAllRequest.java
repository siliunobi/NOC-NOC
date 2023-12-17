package edu.berkeley.kaiju.service.request.message.request;

import edu.berkeley.kaiju.config.Config;
import edu.berkeley.kaiju.config.Config.ReadAtomicAlgorithm;
import edu.berkeley.kaiju.data.DataItem;
import edu.berkeley.kaiju.exception.KaijuException;
import edu.berkeley.kaiju.service.LockManager;
import edu.berkeley.kaiju.service.MemoryStorageEngine;
import edu.berkeley.kaiju.service.MemoryStorageEngine.KeyTimestampPair;
import edu.berkeley.kaiju.service.request.message.KaijuMessage;
import edu.berkeley.kaiju.service.request.message.response.KaijuResponse;

import java.util.Map;

import com.google.common.collect.Lists;

public class PreparePutAllRequest extends KaijuMessage implements IKaijuRequest {
    public Map<String, DataItem> keyValuePairs;

    private PreparePutAllRequest() {}

    public PreparePutAllRequest(Map<String, DataItem> keyValuePairs) {
        this.keyValuePairs = keyValuePairs;
    }

    @Override
    public KaijuResponse processRequest(MemoryStorageEngine storageEngine, LockManager lockManager) throws
                                                                                                    KaijuException {
        if("replica".equals(keyValuePairs.values().stream().findFirst().orElse(DataItem.getNullItem()).getCid())){
            storageEngine.replicaPutAll(keyValuePairs);
            return new KaijuResponse();
        }
        storageEngine.prepare(keyValuePairs);
        if(MemoryStorageEngine.is_ORA() || MemoryStorageEngine.is_NOC()){
            KaijuResponse response = new KaijuResponse();
            response.setHct(storageEngine.getHCT());
            response.senderID = Config.getConfig().server_id;
            return response;
        }
        return new KaijuResponse();
    }
}