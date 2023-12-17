package edu.berkeley.kaiju.data; 

public class TidTimestampPair {
    private Long transaction_id;
    private Long prepared_t;
    
    public  TidTimestampPair(Long transaction_id, Long prepared_t) {
        this.transaction_id = transaction_id;
        this.prepared_t = prepared_t;
    }

    public Long getTransaction_id() {
        return transaction_id;
    }

    public Long getPrepared_t() {
        return prepared_t;
    }
}
