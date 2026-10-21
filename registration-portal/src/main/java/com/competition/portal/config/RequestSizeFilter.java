package com.competition.portal.config;

import com.competition.portal.i18n.Messages;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Refuses request bodies over {@link #MAX_BYTES} with 413: a declared
 * Content-Length is checked up front, a body without one (chunked) is cut
 * off while it is read. The largest legitimate body, a meet's uploaded
 * rankings and catalog or a club's sheet, stays far below the limit.
 */
public class RequestSizeFilter implements Filter {
    public static final long MAX_BYTES = 1024 * 1024;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        if (http.getContentLengthLong() > MAX_BYTES) {
            tooLarge((HttpServletResponse) response);
            return;
        }
        chain.doFilter(http.getContentLengthLong() < 0 ? new Limited(http) : request, response);
    }

    static void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String message = Messages.get("request.tooLarge", MAX_BYTES / 1024).replace("\\", "\\\\").replace("\"", "\\\"");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    /** A request whose body throws once more than MAX_BYTES have been read. */
    private static class Limited extends HttpServletRequestWrapper {
        private ServletInputStream stream;

        Limited(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream in = super.getInputStream();
                stream = new ServletInputStream() {
                    private long read;

                    @Override
                    public int read() throws IOException {
                        int b = in.read();
                        if (b >= 0) {
                            count(1);
                        }
                        return b;
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        int n = in.read(buffer, offset, length);
                        if (n > 0) {
                            count(n);
                        }
                        return n;
                    }

                    private void count(int n) throws IOException {
                        read += n;
                        if (read > MAX_BYTES) {
                            throw new IOException("Request body larger than " + MAX_BYTES + " bytes");
                        }
                    }

                    @Override
                    public boolean isFinished() {
                        return in.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return in.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        in.setReadListener(listener);
                    }
                };
            }
            return stream;
        }
    }
}
