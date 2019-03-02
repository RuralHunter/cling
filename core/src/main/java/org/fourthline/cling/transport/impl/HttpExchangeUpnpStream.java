/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
 *
 * The contents of this file are subject to the terms of either the GNU
 * Lesser General Public License Version 2 or later ("LGPL") or the
 * Common Development and Distribution License Version 1 or later
 * ("CDDL") (collectively, the "License"). You may not use this file
 * except in compliance with the License. See LICENSE.txt for more
 * information.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.fourthline.cling.transport.impl;

import com.sun.net.httpserver.HttpExchange;
import org.fourthline.cling.model.message.Connection;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpHeaders;
import org.fourthline.cling.model.message.UpnpMessage;
import org.fourthline.cling.model.message.UpnpRequest;
import org.fourthline.cling.protocol.ProtocolFactory;
import org.fourthline.cling.transport.spi.UpnpStream;
import org.seamless.util.Exceptions;
import org.seamless.util.io.IO;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Locale;
import java.util.logging.Level;
import org.slf4j.*;

/**
 * Default implementation based on the JDK 6.0 built-in HTTP Server.
 * <p>
 * Instantiated by a <code>com.sun.net.httpserver.HttpHandler</code>.
 * </p>
 *
 * @author Christian Bauer
 */
public abstract class HttpExchangeUpnpStream extends UpnpStream {

    private static Logger log = LoggerFactory.getLogger(UpnpStream.class.getName());

    private HttpExchange httpExchange;

    public HttpExchangeUpnpStream(ProtocolFactory protocolFactory, HttpExchange httpExchange) {
        super(protocolFactory);
        this.httpExchange = httpExchange;
    }

    public HttpExchange getHttpExchange() {
        return httpExchange;
    }

    public void run() {

        try {
            log.debug("Processing HTTP request: " + getHttpExchange().getRequestMethod() + " " + getHttpExchange().getRequestURI());

            // Status
            StreamRequestMessage requestMessage =
                    new StreamRequestMessage(
                            UpnpRequest.Method.getByHttpName(getHttpExchange().getRequestMethod()),
                            getHttpExchange().getRequestURI()
                    );

            if (requestMessage.getOperation().getMethod().equals(UpnpRequest.Method.UNKNOWN)) {
                log.debug("Method not supported by UPnP stack: " + getHttpExchange().getRequestMethod());
                throw new RuntimeException("Method not supported: " + getHttpExchange().getRequestMethod());
            }

            // Protocol
            requestMessage.getOperation().setHttpMinorVersion(
                    getHttpExchange().getProtocol().toUpperCase(Locale.ROOT).equals("HTTP/1.1") ? 1 : 0
            );

            log.debug("Created new request message: " + requestMessage);

            // Connection wrapper
            requestMessage.setConnection(createConnection());

            // Headers
            requestMessage.setHeaders(new UpnpHeaders(getHttpExchange().getRequestHeaders()));

            // Body
            byte[] bodyBytes;
            InputStream is = null;
            try {
                is = getHttpExchange().getRequestBody();
                bodyBytes = IO.readBytes(is);
            } finally {
                if (is != null)
                    is.close();
            }

            log.debug("Reading request body bytes: " + bodyBytes.length);

            if (bodyBytes.length > 0 && requestMessage.isContentTypeMissingOrText()) {

                log.debug("Request contains textual entity body, converting then setting string on message");
                requestMessage.setBodyCharacters(bodyBytes);

            } else if (bodyBytes.length > 0) {

                log.debug("Request contains binary entity body, setting bytes on message");
                requestMessage.setBody(UpnpMessage.BodyType.BYTES, bodyBytes);

            } else {
                log.debug("Request did not contain entity body");
            }

            // Process it
            StreamResponseMessage responseMessage = process(requestMessage);

            // Return the response
            if (responseMessage != null) {
                log.debug("Preparing HTTP response message: " + responseMessage);

                // Headers
                getHttpExchange().getResponseHeaders().putAll(
                        responseMessage.getHeaders()
                );

                // Body
                byte[] responseBodyBytes = responseMessage.hasBody() ? responseMessage.getBodyBytes() : null;
                int contentLength = responseBodyBytes != null ? responseBodyBytes.length : -1;

                log.debug("Sending HTTP response message: " + responseMessage + " with content length: " + contentLength);
                getHttpExchange().sendResponseHeaders(responseMessage.getOperation().getStatusCode(), contentLength);

                if (contentLength > 0) {
                    log.debug("Response message has body, writing bytes to stream...");
                    OutputStream os = null;
                    try {
                        os = getHttpExchange().getResponseBody();
                        IO.writeBytes(os, responseBodyBytes);
                        os.flush();
                    } finally {
                        if (os != null)
                            os.close();
                    }
                }

            } else {
                // If it's null, it's 404, everything else needs a proper httpResponse
                log.debug("Sending HTTP response status: " + HttpURLConnection.HTTP_NOT_FOUND);
                getHttpExchange().sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND, -1);
            }

            responseSent(responseMessage);

        } catch (Throwable t) {

            // You definitely want to catch all Exceptions here, otherwise the server will
            // simply close the socket and you get an "unexpected end of file" on the client.
            // The same is true if you just rethrow an IOException - it is a mystery why it
            // is declared then on the HttpHandler interface if it isn't handled in any
            // way... so we always do error handling here.

            // TODO: We should only send an error if the problem was on our side
            // You don't have to catch Throwable unless, like we do here in unit tests,
            // you might run into Errors as well (assertions).
            log.debug("Exception occured during UPnP stream processing: " + t);
            if (log.isDebugEnabled()) {
                log.debug( "Cause: " + Exceptions.unwrap(t), Exceptions.unwrap(t));
            }
            try {
                httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_INTERNAL_ERROR, -1);
            } catch (IOException ex) {
                log.warn("Couldn't send error response: " + ex);
            }

            responseException(t);
        }
    }

    abstract protected Connection createConnection();

}
