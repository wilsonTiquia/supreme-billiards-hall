package com.supremebilliardshall.billiards_hall_system.security;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.exception.RequestBodyTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

// A ceiling on how large an API request body may be.
//
// Nothing else imposes one. spring.servlet.multipart.max-file-size bounds uploads only, and
// Tomcat's max-http-form-post-size applies solely to form bodies it parses into parameters,
// which this application never asks it to do. A JSON body is therefore unbounded: a 64 MB
// POST to /api/v1/auth/login is accepted and read to completion at the defaults this project
// runs on. That endpoint is the one route reachable without a session, and it deserializes
// into a DTO before it authenticates anybody, so heap use scales with whatever an anonymous
// caller chose to send, times the connections they open.
//
// It matters beyond the memory: the login lockout is a map in this JVM, and its own comment
// justifies losing it on restart on the grounds that an attacker cannot cause one. Without a
// ceiling here, they can — and under KeepAlive supervision the process returns with the
// lockout cleared, which turns a five-attempt limit into an unlimited one.
//
// Ordered ahead of the security chain so an oversized body is refused before any session
// lookup or database work is done on its behalf.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final RequestMatcher API =
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**");

    private final ObjectMapper objectMapper;
    private final long maxBytes;

    public RequestSizeLimitFilter(ObjectMapper objectMapper,
                                  @Value("${supreme.request.max-body-bytes}") long maxBytes) {
        this.objectMapper = objectMapper;
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!applies(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        // The declared length, when there is one. Refusing here costs nothing and means the
        // bytes are never read.
        if (request.getContentLengthLong() > maxBytes) {
            reject(response);
            return;
        }

        // A declared length is not enough on its own: a chunked body carries none, so the
        // stream itself is capped too and a body that runs past the ceiling is cut off
        // mid-read rather than buffered to the end.
        try {
            filterChain.doFilter(new LimitedBodyRequest(request, maxBytes), response);
        } catch (RequestBodyTooLargeException tooLarge) {
            if (!response.isCommitted()) {
                reject(response);
            }
        }
    }

    // The API only. Multipart uploads are governed by the spring.servlet.multipart limits,
    // which are deliberately larger because a payment photo is legitimately bigger than any
    // JSON this application accepts. Everything outside /api/v1 is the static SPA build,
    // which is served by GET and has no body to bound.
    private boolean applies(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            return false;
        }
        return API.matches(request);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.resetBuffer();
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                APIResponse.failure("That request is too large."));
    }

    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final long maxBytes;

        private LimitedBodyRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedInputStream(super.getInputStream(), maxBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding != null ? encoding : StandardCharsets.UTF_8.name()));
        }
    }

    // Counts what has been handed out and stops at the ceiling. Everything else is delegated.
    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        private LimitedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int bytes) throws RequestBodyTooLargeException {
            read += bytes;
            if (read > maxBytes) {
                throw new RequestBodyTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
