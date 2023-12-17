package edu.berkeley.kaiju.service.request.handler;

import edu.berkeley.kaiju.exception.HandlerException;

import java.util.List;
import java.util.Map;

/*
 Clients connect to a front-end server, which executes their transactions for them.

 We extend the IKaijuHandler interface with multiple implementations of read-only and write-only transactions.
 */
public interface IKaijuHandler {
    public Map<String, byte[]> get_all(List<String> keys) throws HandlerException;
    public void put_all(Map<String, byte[]> keyValuePairs) throws HandlerException;
    default void prepare_all(Map<String, byte[]> keyValuePairs, long timestamp) throws HandlerException{
        throw new HandlerException("Unsupported");
    }
    default public void commit_all(Map<String, byte[]> keyValuePairs,long timestamp) throws HandlerException{
        throw new HandlerException("Unsupported");
    }
    default public void addLast(final String key, final Long value, final List<String> keyList) throws HandlerException{
        throw new HandlerException("Unsupported");
    }
}
