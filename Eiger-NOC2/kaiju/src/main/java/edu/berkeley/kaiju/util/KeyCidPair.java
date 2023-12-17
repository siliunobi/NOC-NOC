package edu.berkeley.kaiju.util;

public class KeyCidPair {
    public String key;
    public String cid;

    public KeyCidPair(String key, String cid){
        this.key = key;
        this.cid = cid;
    }

    @Override
    public boolean equals(Object obj){
        if (obj == null)
            return false;
        if (obj == this)
            return true;
        if (!(obj instanceof KeyCidPair))
            return false;

        KeyCidPair a = (KeyCidPair) obj;
        return (a.key == this.key && a.cid == this.cid);
    }
}
