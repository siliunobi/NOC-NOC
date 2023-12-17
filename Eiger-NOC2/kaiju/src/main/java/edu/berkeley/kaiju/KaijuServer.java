
package edu.berkeley.kaiju;

import edu.berkeley.kaiju.config.Config;
import edu.berkeley.kaiju.data.TidTimestampPair;
import edu.berkeley.kaiju.frontend.FrontendServer;
import edu.berkeley.kaiju.monitor.MetricsManager;
import edu.berkeley.kaiju.net.InboundMessagingService;
import edu.berkeley.kaiju.net.routing.OutboundRouter;
import edu.berkeley.kaiju.service.CooperativeCommitter;
import edu.berkeley.kaiju.service.LockManager;
import edu.berkeley.kaiju.service.MemoryStorageEngine;
import edu.berkeley.kaiju.service.request.RequestDispatcher;
import edu.berkeley.kaiju.service.request.RequestExecutorFactory;
import edu.berkeley.kaiju.service.request.eiger.*;
import edu.berkeley.kaiju.service.request.handler.KaijuServiceHandler;
import edu.berkeley.kaiju.util.Timestamp;
import edu.berkeley.kaiju.service.MemoryStorageEngine.KeyTimestampPair;
import com.google.common.collect.Queues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.collect.Maps;

public class KaijuServer {

    private static Logger logger = LoggerFactory.getLogger(KaijuServer.class);

    //LORA
    public static Map<String,Long> last = Maps.newConcurrentMap();

    //ORA
    public static Map<String,Long> prep = Maps.newConcurrentMap();
    public static Map<Integer,Long> hcts = Maps.newConcurrentMap();

    // PORT
    public static Long gst = Timestamp.NO_TIMESTAMP;
    public static Map<String,TidTimestampPair> pending = Maps.newConcurrentMap();
    
    // Thread Local Version
    public static ThreadLocal<Long> gstLocal = new ThreadLocal<Long>() {
        @Override
        protected Long initialValue() {
            return Timestamp.NO_TIMESTAMP;
        }
    };
    public static ThreadLocal<Map<String,TidTimestampPair>> pendingLocal = new ThreadLocal<Map<String,TidTimestampPair>>() {
        @Override
        protected Map<String,TidTimestampPair> initialValue() {
            return Maps.newConcurrentMap();
        }
    };
    public static ThreadLocal<Map<Integer,Long>> hctsLocal = new ThreadLocal<Map<Integer,Long>>() {
        @Override
        protected Map<Integer,Long> initialValue() {
            return Maps.newConcurrentMap();
        }
    };

    public static AtomicBoolean hasEnded = new AtomicBoolean(false);
    public static void main(String[] args) {
        Config.serverSideInitialize(args);

        MetricsManager.initializeMetrics();

        MemoryStorageEngine storage = new MemoryStorageEngine();
        LockManager lockManager = new LockManager();
        RequestExecutorFactory requestExecutorFactory = new RequestExecutorFactory(storage, lockManager);
        RequestDispatcher dispatcher = new RequestDispatcher(requestExecutorFactory);
        storage.setDispatcher(dispatcher);
        if(Config.getConfig().readatomic_algorithm == Config.ReadAtomicAlgorithm.EIGER_PORT)
            requestExecutorFactory.setEigerExecutor(new EigerPortExecutor(dispatcher, storage));
        else if(Config.getConfig().readatomic_algorithm == Config.ReadAtomicAlgorithm.EIGER_PORT_PLUS)
            requestExecutorFactory.setEigerExecutor(new EigerPortPlusExecutor(dispatcher, storage));
        else if(Config.getConfig().readatomic_algorithm == Config.ReadAtomicAlgorithm.EIGER_PORT_PLUS_PLUS)
            requestExecutorFactory.setEigerExecutor(new EigerPortPlusPlusExecutor(dispatcher, storage));
        else
            requestExecutorFactory.setEigerExecutor(new EigerExecutor(dispatcher, storage));

        new CooperativeCommitter(storage, new KaijuServiceHandler(dispatcher, storage, lockManager));
        
        try {
            InboundMessagingService.start(dispatcher);
        } catch (IOException e) {
            logger.error("Error starting inbound messaging service", e);
            System.exit(-1);
        }

        logger.info("Started listening for connections...");

        try {
            Thread.sleep(Config.getConfig().bootstrap_time);
        } catch (InterruptedException e) {
            logger.warn("Bootstrap interrupted", e);
        }

        try {
            OutboundRouter.initializeRouter();
        } catch (IOException e) {
            logger.error("Error starting outbound messaging service", e);
            System.exit(-1);
        }

        logger.info("Initialized Kaiju internal services; starting Thrift server");

        KaijuServiceHandler handler = new KaijuServiceHandler(dispatcher, storage, lockManager);

        try {
            new FrontendServer(handler, Config.getConfig().thrift_port).serve();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
