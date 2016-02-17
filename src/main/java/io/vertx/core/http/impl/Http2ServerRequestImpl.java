/*
 * Copyright (c) 2011-2013 The original author or authors
 *  ------------------------------------------------------
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.core.http.impl;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Stream;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerFileUpload;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.cert.X509Certificate;
import java.net.URISyntaxException;
import java.util.ArrayDeque;

import static io.netty.handler.codec.http.HttpScheme.HTTPS;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class Http2ServerRequestImpl extends VertxHttp2Stream implements HttpServerRequest {

  private static final Logger log = LoggerFactory.getLogger(HttpServerRequestImpl.class);
  private static final Object END = new Object(); // Marker

  private final Vertx vertx;
  private final VertxHttp2Handler connection;
  private final String serverOrigin;
  private final ChannelHandlerContext ctx;
  private final Http2Connection conn;
  private final Http2Stream stream;
  private final Http2ServerResponseImpl response;
  private final Http2Headers headers;
  private MultiMap headersMap;
  private MultiMap params;
  private HttpMethod method;
  private String absoluteURI;
  private String uri;
  private String path;
  private String query;
  private MultiMap attributes;

  private Handler<Buffer> dataHandler;
  private Handler<Void> endHandler;
  private boolean paused;
  private boolean ended;
  private ArrayDeque<Object> pending = new ArrayDeque<>(8);

  private Handler<HttpServerFileUpload> uploadHandler;
  private HttpPostRequestDecoder decoder;

  public Http2ServerRequestImpl(
      Vertx vertx,
      VertxHttp2Handler connection,
      String serverOrigin,
      Http2Connection conn,
      Http2Stream stream,
      ChannelHandlerContext ctx,
      Http2ConnectionEncoder encoder,
      Http2Headers headers) {
    this.vertx = vertx;
    this.connection = connection;
    this.serverOrigin = serverOrigin;
    this.conn = conn;
    this.stream = stream;
    this.headers = headers;
    this.ctx = ctx;
    this.response = new Http2ServerResponseImpl(ctx, encoder, stream);
  }

  void end() {
    if (paused || pending.size() > 0) {
      pending.add(END);
    } else {
      callEnd();
    }
  }

  boolean handleData(Buffer data) {
    if (!paused) {
      if (pending.isEmpty()) {
        callHandler(data);
        return true;
      } else {
        pending.add(data);
        checkNextTick(null);
      }
    } else {
      pending.add(data);
    }
    return false;
  }

  void handleReset(long code) {
    ended = true;
    paused = false;
    pending.clear();
    response.handleReset(code);
    if (endHandler != null) {
      endHandler.handle(null);
    }
  }

  private void callHandler(Buffer data) {
    if (decoder != null) {
      try {
        decoder.offer(new DefaultHttpContent(data.getByteBuf()));
      } catch (HttpPostRequestDecoder.ErrorDataDecoderException e) {
        e.printStackTrace();
      }
    }
    if (dataHandler != null) {
      dataHandler.handle(data);
    }
  }

  private void callEnd() {
    ended = true;
    if (decoder != null) {
      try {
        decoder.offer(LastHttpContent.EMPTY_LAST_CONTENT);
        while (decoder.hasNext()) {
          InterfaceHttpData data = decoder.next();
          if (data instanceof Attribute) {
            Attribute attr = (Attribute) data;
            try {
              formAttributes().add(attr.getName(), attr.getValue());
            } catch (Exception e) {
              // Will never happen, anyway handle it somehow just in case
              handleException(e);
            }
          }
        }
      } catch (HttpPostRequestDecoder.ErrorDataDecoderException e) {
        handleException(e);
      } catch (HttpPostRequestDecoder.EndOfDataDecoderException e) {
        // ignore this as it is expected
      } finally {
        decoder.destroy();
      }
    }
    if (endHandler != null) {
      endHandler.handle(null);
    }
  }

  private void handleException(Throwable t) {
    t.printStackTrace();
  }

  private void checkNextTick(Void v) {
    if (!paused) {
      Object msg = pending.poll();
      if (msg instanceof Buffer) {
        Buffer buf = (Buffer) msg;
        try {
          boolean windowUpdateSent = conn.local().flowController().consumeBytes(stream, buf.length());
          if (windowUpdateSent) {
            ctx.flush();
          }
        } catch (Http2Exception e) {
          e.printStackTrace();
        }
        callHandler(buf);
        if (pending.size() > 0) {
          vertx.runOnContext(this::checkNextTick);
        }
      } if (msg == END) {
        callEnd();
      }
    }
  }

  @Override
  public HttpServerRequest exceptionHandler(Handler<Throwable> handler) {
    throw new UnsupportedOperationException();
  }

  @Override
  public HttpServerRequest handler(Handler<Buffer> handler) {
    dataHandler = handler;
    return this;
  }

  @Override
  public HttpServerRequest pause() {
    paused = true;
    return this;
  }

  @Override
  public HttpServerRequest resume() {
    paused = false;
    checkNextTick(null);
    return this;
  }

  @Override
  public HttpServerRequest endHandler(Handler<Void> handler) {
    endHandler = handler;
    return this;
  }

  @Override
  public HttpVersion version() {
    return HttpVersion.HTTP_2;
  }

  @Override
  public HttpMethod method() {
    if (method == null) {
      String sMethod = headers.method().toString();
      try {
        method = io.vertx.core.http.HttpMethod.valueOf(sMethod);
      } catch (IllegalArgumentException e) {
        method = HttpMethod.UNKNOWN;
      }
    }
    return method;
  }

  @Override
  public boolean isSSL() {
    return headers.scheme().equals(HTTPS.name());
  }

  @Override
  public String uri() {
    if (uri == null) {
      uri = headers.path().toString();
    }
    return uri;
  }

  @Override
  public String path() {
    if (path == null) {
      path = UriUtils.parsePath(uri());
    }
    return path;
  }

  @Override
  public @Nullable String query() {
    if (query == null) {
      query = UriUtils.parseQuery(uri());
    }
    return query;
  }

  @Override
  public Http2ServerResponseImpl response() {
    return response;
  }

  @Override
  public MultiMap headers() {
    if (headersMap == null) {
      headersMap = new Http2HeadersAdaptor(headers);
    }
    return headersMap;
  }

  @Override
  public @Nullable String getHeader(String headerName) {
    return headers().get(headerName);
  }

  @Override
  public String getHeader(CharSequence headerName) {
    return headers().get(headerName);
  }

  @Override
  public MultiMap params() {
    if (params == null) {
      params = UriUtils.params(uri());
    }
    return params;
  }

  @Override
  public @Nullable String getParam(String paramName) {
    return params().get(paramName);
  }

  @Override
  public SocketAddress remoteAddress() {
    throw new UnsupportedOperationException();
  }

  @Override
  public SocketAddress localAddress() {
    throw new UnsupportedOperationException();
  }

  @Override
  public X509Certificate[] peerCertificateChain() throws SSLPeerUnverifiedException {
    throw new UnsupportedOperationException();
  }

  @Override
  public String absoluteURI() {
    if (absoluteURI == null) {
      try {
        absoluteURI = UriUtils.absoluteURI(serverOrigin, this);
      } catch (URISyntaxException e) {
        log.error("Failed to create abs uri", e);
      }
    }
    return absoluteURI;
  }

  @Override
  public NetSocket netSocket() {
    throw new UnsupportedOperationException();
  }

  @Override
  public HttpServerRequest setExpectMultipart(boolean expect) {
    if (expect) {
      if (decoder == null) {
        CharSequence contentType = headers.get(HttpHeaderNames.CONTENT_TYPE);
        if (contentType != null) {
          io.netty.handler.codec.http.HttpMethod method = io.netty.handler.codec.http.HttpMethod.valueOf(headers.method().toString());
          String lowerCaseContentType = contentType.toString().toLowerCase();
          boolean isURLEncoded = lowerCaseContentType.startsWith(HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.toString());
          if ((lowerCaseContentType.startsWith(HttpHeaderValues.MULTIPART_FORM_DATA.toString()) || isURLEncoded) &&
              (method == io.netty.handler.codec.http.HttpMethod.POST ||
                  method == io.netty.handler.codec.http.HttpMethod.PUT ||
                  method == io.netty.handler.codec.http.HttpMethod.PATCH ||
                  method == io.netty.handler.codec.http.HttpMethod.DELETE)) {
            HttpRequest req = new DefaultHttpRequest(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                method,
                headers.path().toString());
            req.headers().add(HttpHeaderNames.CONTENT_TYPE, contentType);
            decoder = new HttpPostRequestDecoder(new NettyFileUploadDataFactory(vertx, this, () -> uploadHandler), req);
          }
        }
      }
    } else {
      decoder = null;
    }
    return this;
  }

  @Override
  public boolean isExpectMultipart() {
    return decoder != null;
  }

  @Override
  public HttpServerRequest uploadHandler(@Nullable Handler<HttpServerFileUpload> handler) {
    uploadHandler = handler;
    return this;
  }

  @Override
  public MultiMap formAttributes() {
    // Create it lazily
    if (attributes == null) {
      attributes = new CaseInsensitiveHeaders();
    }
    return attributes;
  }

  @Override
  public @Nullable String getFormAttribute(String attributeName) {
    return formAttributes().get(attributeName);
  }

  @Override
  public ServerWebSocket upgrade() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isEnded() {
    throw new UnsupportedOperationException();
  }

  @Override
  public HttpConnection connection() {
    return connection;
  }

  @Override
  public HttpServerRequest promisePush(HttpMethod method, String path, Handler<AsyncResult<HttpServerResponse>> handler) {
    Http2Headers headers = new DefaultHttp2Headers();
    headers.method(method.name());
    headers.path(path);
    int id = conn.local().nextStreamId();
    connection.encoder().writePushPromise(ctx, stream.id(), id, headers, 0, ctx.newPromise()).addListener(fut -> {
      if (fut.isSuccess()) {
        connection.schedulePush(ctx, id, handler);
      } else {
        handler.handle(Future.failedFuture(fut.cause()));
      }
    });
    return this;
  }
}