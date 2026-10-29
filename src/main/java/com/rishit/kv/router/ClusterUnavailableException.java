package com.rishit.kv.router;

/** Thrown when not enough replicas are reachable to complete a request. */
public class ClusterUnavailableException extends Exception {

    public ClusterUnavailableException(String message) {
        super(message);
    }
}
