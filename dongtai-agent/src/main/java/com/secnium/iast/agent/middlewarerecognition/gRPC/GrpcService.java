package com.secnium.iast.agent.middlewarerecognition.gRPC;

import com.secnium.iast.agent.middlewarerecognition.IServer;

import java.lang.management.RuntimeMXBean;

public class GrpcService implements IServer {
    @Override
    public boolean isMatch(RuntimeMXBean paramRuntimeMXBean) {
        try {
            Class<?> classOfGrpcServer = Thread.currentThread().getContextClassLoader().loadClass("io.grpc.internal.ServerImpl");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public String getName() {
        return "gRPC";
    }

    @Override
    public String getVersion() {
        return "";
    }
}
