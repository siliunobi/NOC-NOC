package edu.berkeley.kaiju.data;


public class Transaction {

    private enum TransactionType {
        READ,
        WRITE,
    }

    private String key;
    private Long timestamp;
    private String client_id;
    private Long transaction_id;
    private TransactionType type;

    public Transaction(String key, Long timestamp, String client_id, Long transaction_id, String type) {
        this.key = key;
        this.timestamp = timestamp;
        this.client_id = client_id;
        this.transaction_id = transaction_id;
        this.type = (type.equals("READ")) ? TransactionType.READ : TransactionType.WRITE;
    }

    public String getKey() {
        return key;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public String getClientId() {
        return client_id;
    }

    public Long getTransactionId() {
        return transaction_id;
    }

    public TransactionType getType() {
        return type;
    }

    public String toString() {
        return transaction_id + "," + type + "," + client_id + "," + key + "," + timestamp + "\n";
    }
}
