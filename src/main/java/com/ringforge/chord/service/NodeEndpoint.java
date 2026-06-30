package com.ringforge.chord.service;

import java.net.URI;

public final class NodeEndpoint {
    private final int nodeId;
    private final URI baseUri;

    public NodeEndpoint(int nodeId, URI baseUri) {
        this.nodeId = nodeId;
        this.baseUri = baseUri;
    }

    public int nodeId() {
        return nodeId;
    }

    public URI baseUri() {
        return baseUri;
    }
}
