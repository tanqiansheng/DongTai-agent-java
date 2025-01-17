package io.dongtai.api.servlet2;

import io.dongtai.api.DongTaiResponse;
import io.dongtai.iast.common.config.*;
import io.dongtai.iast.common.constants.AgentConstant;
import io.dongtai.log.DongTaiLog;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.*;
import java.util.*;

public class ServletResponseWrapper extends HttpServletResponseWrapper implements DongTaiResponse {

    private ServletOutputStream outputStream = null;
    private PrintWriter writer = null;
    private ServletWrapperOutputStreamCopier copier = null;

    @SuppressWarnings("unchecked")
    public ServletResponseWrapper(HttpServletResponse response) {
        super(response);
        try {
            boolean enableVersionHeader = ((Config<Boolean>) ConfigBuilder.getInstance()
                    .getConfig(ConfigKey.ENABLE_VERSION_HEADER)).get();
            if (enableVersionHeader) {
                String versionHeaderKey = ((Config<String>) ConfigBuilder.getInstance()
                        .getConfig(ConfigKey.VERSION_HEADER_KEY)).get();
                response.addHeader(versionHeaderKey, AgentConstant.VERSION_VALUE);
            }
        } catch (Throwable ignore) {
        }
    }

    private String getLine() {
        return "HTTP/1.1" + " " + getStatus() + "\n";
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            DongTaiLog.warn("ServletResponseWrapper getOutputStream() has already been called over once");
        }
        if (outputStream == null) {
            outputStream = getResponse().getOutputStream();
            copier = new ServletWrapperOutputStreamCopier(outputStream);
        }
        return copier;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (outputStream != null) {
            DongTaiLog.warn("ServletResponseWrapper getWriter() has already been called over once");
        }
        if (writer == null) {
            copier = new ServletWrapperOutputStreamCopier(getResponse().getOutputStream());
            writer = new PrintWriter(new OutputStreamWriter(copier, getResponse().getCharacterEncoding()), true);
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        } else if (copier != null) {
            copier.flush();
        }
    }

    @Override
    public Map<String, Object> getResponseMeta(boolean getBody) {
        Map<String, Object> responseMeta = new HashMap<String, Object>(2);
        responseMeta.put("headers", getHeaders());
        responseMeta.put("body", getResponseData(getBody));
        return responseMeta;
    }

    private String getHeaders() {
        StringBuilder header = new StringBuilder();
        Collection<String> headerNames = getHeaderNames();
        header.append(getLine());
        for (String headerName : headerNames) {
            header.append(headerName).append(":").append(getHeader(headerName)).append("\n");
        }
        return header.toString();
    }

    @Override
    public byte[] getResponseData(boolean getBody) {
        try {
            flushBuffer();
        } catch (Throwable e) {
            DongTaiLog.error("ServletResponseWrapper flushBuffer failed", e);
        }
        if (getBody && copier != null) {
            return copier.getCopy();
        }
        return new byte[0];
    }
}
