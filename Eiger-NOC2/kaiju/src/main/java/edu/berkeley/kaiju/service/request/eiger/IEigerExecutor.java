package edu.berkeley.kaiju.service.request.eiger;
import java.io.IOException;

import edu.berkeley.kaiju.data.DataItem;
import edu.berkeley.kaiju.exception.KaijuException;
import edu.berkeley.kaiju.service.request.message.request.EigerCheckCommitRequest;
import edu.berkeley.kaiju.service.request.message.request.EigerCommitRequest;
import edu.berkeley.kaiju.service.request.message.request.EigerGetAllRequest;
import edu.berkeley.kaiju.service.request.message.request.EigerPutAllRequest;
import edu.berkeley.kaiju.service.request.message.response.EigerPreparedResponse;

public interface IEigerExecutor {

    public void processMessage(EigerPutAllRequest putAllRequest) throws KaijuException, IOException, InterruptedException;

    public void processMessage(EigerPreparedResponse preparedNotification) throws KaijuException, IOException, InterruptedException;

    public void processMessage(EigerCommitRequest commitNotification) throws KaijuException, IOException, InterruptedException;

    public void processMessage(EigerGetAllRequest getAllRequest) throws KaijuException, IOException, InterruptedException;        

    public void processMessage(EigerCheckCommitRequest checkCommitRequest) throws KaijuException, IOException, InterruptedException;

    public void logFreshness(String key, DataItem value);

    public void logTransaction(String key, Long timestamp, String client_id, Long transaction_id, String type);
}
