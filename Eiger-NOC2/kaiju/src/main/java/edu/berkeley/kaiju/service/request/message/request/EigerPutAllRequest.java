package edu.berkeley.kaiju.service.request.message.request;

import edu.berkeley.kaiju.config.Config;
import edu.berkeley.kaiju.data.DataItem;
import edu.berkeley.kaiju.exception.KaijuException;
import edu.berkeley.kaiju.service.LockManager;
import edu.berkeley.kaiju.service.MemoryStorageEngine;
import edu.berkeley.kaiju.service.request.message.KaijuMessage;
import edu.berkeley.kaiju.service.request.message.response.KaijuResponse;

import java.util.Map;

public class EigerPutAllRequest extends KaijuMessage implements IKaijuRequest {
    public Map<String, DataItem> keyValuePairs;
    public String coordinatorKey;
    public int totalNumKeys;
    public String client_id;
    public boolean is_get;
    public Long readTimestamp;

    private EigerPutAllRequest() {}

    public EigerPutAllRequest(Map<String, DataItem> keyValuePairs, String coordinatorKey, int totalNumKeys) {
        this.keyValuePairs = keyValuePairs;
        this.coordinatorKey = coordinatorKey;
        this.totalNumKeys = totalNumKeys;
        this.is_get = false;
        this.client_id = Config.getConfig().server_id.toString(); //+ ":" + Long.valueOf(Thread.currentThread().getId()).toString();
    }

    public EigerPutAllRequest(Map<String,DataItem> keyValuePairs, Long readTimestamp){
        this.is_get = true;
        this.keyValuePairs = keyValuePairs;
        this.readTimestamp = readTimestamp;
        this.client_id = Config.getConfig().server_id.toString(); //+ ":" + Long.valueOf(Thread.currentThread().getId()).toString();
    }

    @Override
    public KaijuResponse processRequest(MemoryStorageEngine storageEngine, LockManager lockManager) throws
                                                                                                    KaijuException {
        throw new RuntimeException("SHOULD NOT BE CALLED");
    }
}