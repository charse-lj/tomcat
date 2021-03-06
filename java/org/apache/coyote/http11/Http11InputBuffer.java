/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http11;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.apache.coyote.CloseNowException;
import org.apache.coyote.InputBuffer;
import org.apache.coyote.Request;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.HeaderUtil;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.parser.HttpParser;
import org.apache.tomcat.util.net.ApplicationBufferHandler;
import org.apache.tomcat.util.net.SocketWrapperBase;
import org.apache.tomcat.util.res.StringManager;

/**
 * InputBuffer for HTTP that provides request header parsing as well as transfer
 * encoding.
 */
public class Http11InputBuffer implements InputBuffer, ApplicationBufferHandler {

    // -------------------------------------------------------------- Constants

    private static final Log log = LogFactory.getLog(Http11InputBuffer.class);

    /**
     * The string manager for this package.
     */
    private static final StringManager sm = StringManager.getManager(Http11InputBuffer.class);


    private static final byte[] CLIENT_PREFACE_START =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * Associated Coyote request.
     */
    private final Request request;


    /**
     * Headers of the associated request.
     */
    private final MimeHeaders headers;


    private final boolean rejectIllegalHeader;

    /**
     * State.
     */
    private boolean parsingHeader;


    /**
     * Swallow input ? (in the case of an expectation)
     */
    private boolean swallowInput;


    /**
     * The read buffer.
     */
    private ByteBuffer byteBuffer;


    /**
     * Pos of the end of the header in the buffer, which is also the
     * start of the body.
     */
    private int end;


    /**
     * Wrapper that provides access to the underlying socket.
     */
    private SocketWrapperBase<?> wrapper;


    /**
     * Underlying input buffer.
     */
    private InputBuffer inputStreamInputBuffer;


    /**
     * Filter library.
     * Note: Filter[Constants.CHUNKED_FILTER] is always the "chunked" filter.
     */
    private InputFilter[] filterLibrary;


    /**
     * Active filters (in order).
     */
    private InputFilter[] activeFilters;


    /**
     * Index of the last active filter.
     */
    private int lastActiveFilter;


    /**
     * Parsing state - used for non blocking parsing so that
     * when more data arrives, we can pick up where we left off.
     */
    private byte prevChr = 0;
    private byte chr = 0;
    private boolean parsingRequestLine;
    private int parsingRequestLinePhase = 0;
    private boolean parsingRequestLineEol = false;
    // ????????????????????????ByteBuffer????????????(position)
    private int parsingRequestLineStart = 0;
    private int parsingRequestLineQPos = -1;
    private HeaderParsePosition headerParsePos;
    private final HeaderParseData headerData = new HeaderParseData();
    private final HttpParser httpParser;

    /**
     * Maximum allowed size of the HTTP request line plus headers plus any leading blank lines.
     */
    // ?????????????????????HTTP????????? + header + ??????????????????????????????8K???????????????org.apache.coyote.http11.AbstractHttp11Protocol.maxHttpHeaderSize???
    private final int headerBufferSize;

    /**
     * Known size of the NioChannel read buffer.
     */
    private int socketReadBufferSize;


    // ----------------------------------------------------------- Constructors

    public Http11InputBuffer(Request request, int headerBufferSize,
            boolean rejectIllegalHeader, HttpParser httpParser) {

        this.request = request;
        /**
         * ???request?????????headers
         */
        headers = request.getMimeHeaders();

        this.headerBufferSize = headerBufferSize;
        this.rejectIllegalHeader = rejectIllegalHeader;
        this.httpParser = httpParser;

        filterLibrary = new InputFilter[0];
        activeFilters = new InputFilter[0];
        lastActiveFilter = -1;

        parsingHeader = true;
        parsingRequestLine = true;
        parsingRequestLinePhase = 0;
        parsingRequestLineEol = false;
        parsingRequestLineStart = 0;
        parsingRequestLineQPos = -1;
        headerParsePos = HeaderParsePosition.HEADER_START;
        swallowInput = true;

        inputStreamInputBuffer = new SocketInputBuffer();
    }


    // ------------------------------------------------------------- Properties

    /**
     * Add an input filter to the filter library.
     *
     * @throws NullPointerException if the supplied filter is null
     */
    void addFilter(InputFilter filter) {

        if (filter == null) {
            throw new NullPointerException(sm.getString("iib.filter.npe"));
        }

        InputFilter[] newFilterLibrary = Arrays.copyOf(filterLibrary, filterLibrary.length + 1);
        newFilterLibrary[filterLibrary.length] = filter;
        filterLibrary = newFilterLibrary;

        activeFilters = new InputFilter[filterLibrary.length];
    }


    /**
     * Get filters.
     */
    InputFilter[] getFilters() {
        return filterLibrary;
    }


    /**
     * Add an input filter to the filter library.
     */
    void addActiveFilter(InputFilter filter) {

        if (lastActiveFilter == -1) {
            filter.setBuffer(inputStreamInputBuffer);
        } else {
            for (int i = 0; i <= lastActiveFilter; i++) {
                if (activeFilters[i] == filter)
                    return;
            }
            filter.setBuffer(activeFilters[lastActiveFilter]);
        }

        activeFilters[++lastActiveFilter] = filter;

        filter.setRequest(request);
    }


    /**
     * Set the swallow input flag.
     */
    void setSwallowInput(boolean swallowInput) {
        this.swallowInput = swallowInput;
    }


    // ---------------------------------------------------- InputBuffer Methods

    @Override
    public int doRead(ApplicationBufferHandler handler) throws IOException {

        if (lastActiveFilter == -1)
            return inputStreamInputBuffer.doRead(handler);
        else
            return activeFilters[lastActiveFilter].doRead(handler);

    }


    // ------------------------------------------------------- Protected Methods

    /**
     * Recycle the input buffer. This should be called when closing the
     * connection.
     */
    void recycle() {
        wrapper = null;
        request.recycle();

        for (int i = 0; i <= lastActiveFilter; i++) {
            activeFilters[i].recycle();
        }

        byteBuffer.limit(0).position(0);
        lastActiveFilter = -1;
        parsingHeader = true;
        swallowInput = true;

        chr = 0;
        prevChr = 0;
        headerParsePos = HeaderParsePosition.HEADER_START;
        parsingRequestLine = true;
        parsingRequestLinePhase = 0;
        parsingRequestLineEol = false;
        parsingRequestLineStart = 0;
        parsingRequestLineQPos = -1;
        headerData.recycle();
    }


    /**
     * End processing of current HTTP request.
     * Note: All bytes of the current request should have been already
     * consumed. This method only resets all the pointers so that we are ready
     * to parse the next HTTP request.
     */
    void nextRequest() {
        request.recycle();

        if (byteBuffer.position() > 0) {
            if (byteBuffer.remaining() > 0) {
                // Copy leftover bytes to the beginning of the buffer
                byteBuffer.compact();
                byteBuffer.flip();
            } else {
                // Reset position and limit to 0
                byteBuffer.position(0).limit(0);
            }
        }

        // Recycle filters
        for (int i = 0; i <= lastActiveFilter; i++) {
            activeFilters[i].recycle();
        }

        // Reset pointers
        lastActiveFilter = -1;
        parsingHeader = true;
        swallowInput = true;

        headerParsePos = HeaderParsePosition.HEADER_START;
        parsingRequestLine = true;
        parsingRequestLinePhase = 0;
        parsingRequestLineEol = false;
        parsingRequestLineStart = 0;
        parsingRequestLineQPos = -1;
        headerData.recycle();
    }


    /**
     * Read the request line. This function is meant to be used during the
     * HTTP request header parsing. Do NOT attempt to read the request body
     * using it.
     *
     * @throws IOException If an exception occurs during the underlying socket
     * read operations, or if the given buffer is not big enough to accommodate
     * the whole line.
     *
     * @return true if data is properly fed; false if no data is available
     * immediately and thread should be freed
     */
    /**
     * ???????????????????????????????????????HTTP???????????????????????????????????????????????????????????????
     *
     * @param keptAlive
     * @param connectionTimeout
     * @param keepAliveTimeout
     * @return ?????????????????????????????????true????????????false????????????????????????????????????????????????????????????????????????false
     * @throws IOException
     */
    // ?????????https://blog.csdn.net/weixin_34203832/article/details/92295554
    boolean parseRequestLine(boolean keptAlive, int connectionTimeout, int keepAliveTimeout)
            throws IOException {

        // check state
        if (!parsingRequestLine) {
            return true;
        }
        //
        // Skipping blank lines
        //
        if (parsingRequestLinePhase < 2) {
            do {
                // Read new bytes if needed  ?????????????????????????????????
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (keptAlive) {
                        // Haven't read any request data yet so use the keep-alive timeout.  (??????????????????????????????????????????????????????????????????)
                        wrapper.setReadTimeout(keepAliveTimeout);
                    }
                    if (!fill(false)) {
                        // ??????????????????
                        // A read is pending, so no longer in initial state  ???????????????????????????????????????????????????????????????
                        parsingRequestLinePhase = 1;
                        return false;
                    }
                    // At least one byte of the request has been received. Switch to the socket timeout.
                    // ??????????????????????????????????????? ?????????socket?????????
                    wrapper.setReadTimeout(connectionTimeout);
                }
                if (!keptAlive && byteBuffer.position() == 0 && byteBuffer.limit() >= CLIENT_PREFACE_START.length - 1) {
                    boolean prefaceMatch = true;
                    for (int i = 0; i < CLIENT_PREFACE_START.length && prefaceMatch; i++) {
                        if (CLIENT_PREFACE_START[i] != byteBuffer.get(i)) {
                            prefaceMatch = false;
                        }
                    }
                    // ????????????????????????????????????CLIENT_PREFACE_START????????????????????????false
                    if (prefaceMatch) {
                        // HTTP/2 preface matched
                        parsingRequestLinePhase = -1;
                        return false;
                    }
                }
                // Set the start time once we start reading data (even if it is just skipping blank lines)
                // ???????????????????????????????????????????????????????????????????????????????????????
                if (request.getStartTime() < 0) {
                    request.setStartTime(System.currentTimeMillis());
                }
                // ???????????????????????????position+1???
                chr = byteBuffer.get();
            } while ((chr == Constants.CR) || (chr == Constants.LF));  // ?????????/r???/n???????????????????????????????????????????????????????????????????????????while
            // ?????????????????????????????????????????????????????????get()????????????chr??????????????????position+1??????
            byteBuffer.position(byteBuffer.position() - 1);

            // ??????
            parsingRequestLineStart = byteBuffer.position();
            // ????????????2
            parsingRequestLinePhase = 2;
            if (log.isDebugEnabled()) {
                // debug????????????????????????????????????\r???\n???
                // offset??????position???length??????remaining
                log.debug("Received ["
                        + new String(byteBuffer.array(), byteBuffer.position(), byteBuffer.remaining(), StandardCharsets.ISO_8859_1) + "]");
            }
        }
        /**
         * ??????2????????????????????????POST ??? GET
         *
         * ???parsingRequestLineStart?????????????????????????????????????????????????????????????????????????????????????????????
         */
        if (parsingRequestLinePhase == 2) {
            //
            // Reading the method name
            // Method name is a token
            //
            boolean space = false;
            while (!space) {
                // Read new bytes if needed?????????????????????????????????????????????????????????fill?????????
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (!fill(false)) // request line parsing
                        return false;
                }
                // Spec says method name is a token followed by a single SP but also be tolerant of multiple SP and/or HT.
                // Spec?????????????????????????????????????????????????????????????????????????????? ?????? ???/??? \r???
                int pos = byteBuffer.position();
                chr = byteBuffer.get();
                if (chr == Constants.SP || chr == Constants.HT) {
                    // ????????????\t
                    space = true;
                    request.method().setBytes(byteBuffer.array(), parsingRequestLineStart,
                            pos - parsingRequestLineStart);
                } else if (!HttpParser.isToken(chr)) {
                    // Avoid unknown protocol triggering an additional error
                    request.protocol().setString(Constants.HTTP_11);
                    String invalidMethodValue = parseInvalid(parsingRequestLineStart, byteBuffer);
                    throw new IllegalArgumentException(sm.getString("iib.invalidmethod", invalidMethodValue));
                }
            }
            parsingRequestLinePhase = 3;
        }
        /**
         * ??????3?????????????????????????????????????????????????????????parsingRequestLineStart??????
         */
        if (parsingRequestLinePhase == 3) {
            // Spec says single SP but also be tolerant of multiple SP and/or HT
            boolean space = true;
            while (space) {
                // Read new bytes if needed?????????????????????????????????????????????????????????fill?????????
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (!fill(false)) // request line parsing
                        return false;
                }
                chr = byteBuffer.get();
                if (!(chr == Constants.SP || chr == Constants.HT)) {
                    space = false;
                    byteBuffer.position(byteBuffer.position() - 1);
                }
            }
            parsingRequestLineStart = byteBuffer.position();
            parsingRequestLinePhase = 4;
        }
        /**
         * ??????4?????????URI?????????URI???????????????queryString???
         */
        if (parsingRequestLinePhase == 4) {
            // Mark the current buffer position

            int end = 0;
            //
            // Reading the URI  (??????URI)
            //
            boolean space = false;
            while (!space) {
                // Read new bytes if needed?????????????????????????????????????????????????????????fill?????????
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (!fill(false)) // request line parsing
                        return false;
                }
                int pos = byteBuffer.position();
                prevChr = chr;
                chr = byteBuffer.get();
                if (prevChr == Constants.CR && chr != Constants.LF) {
                    // CR not followed by LF so not an HTTP/0.9 request and
                    // therefore invalid. Trigger error handling.
                    // Avoid unknown protocol triggering an additional error
                    request.protocol().setString(Constants.HTTP_11);
                    String invalidRequestTarget = parseInvalid(parsingRequestLineStart, byteBuffer);
                    throw new IllegalArgumentException(sm.getString("iib.invalidRequestTarget", invalidRequestTarget));
                }
                if (chr == Constants.SP || chr == Constants.HT) {
                    space = true;
                    end = pos;
                } else if (chr == Constants.CR) {
                    // HTTP/0.9 style request. CR is optional. LF is not.
                } else if (chr == Constants.LF) {
                    // HTTP/0.9 style request
                    // Stop this processing loop
                    space = true;
                    // Set blank protocol (indicates HTTP/0.9)
                    request.protocol().setString("");
                    // Skip the protocol processing
                    parsingRequestLinePhase = 7;
                    if (prevChr == Constants.CR) {
                        end = pos - 1;
                    } else {
                        end = pos;
                    }
                } else if (chr == Constants.QUESTION && parsingRequestLineQPos == -1) {  // ????????????"?"??????
                    parsingRequestLineQPos = pos;
                } else if (parsingRequestLineQPos != -1 && !httpParser.isQueryRelaxed(chr)) {
                    // Avoid unknown protocol triggering an additional error
                    request.protocol().setString(Constants.HTTP_11);
                    // %nn decoding will be checked at the point of decoding
                    String invalidRequestTarget = parseInvalid(parsingRequestLineStart, byteBuffer);
                    throw new IllegalArgumentException(sm.getString("iib.invalidRequestTarget", invalidRequestTarget));
                } else if (httpParser.isNotRequestTargetRelaxed(chr)) {
                    // Avoid unknown protocol triggering an additional error
                    request.protocol().setString(Constants.HTTP_11);
                    // This is a general check that aims to catch problems early
                    // Detailed checking of each part of the request target will
                    // happen in Http11Processor#prepareRequest()
                    String invalidRequestTarget = parseInvalid(parsingRequestLineStart, byteBuffer);
                    throw new IllegalArgumentException(sm.getString("iib.invalidRequestTarget", invalidRequestTarget));
                }
            }
            if (parsingRequestLineQPos >= 0) {
                // ??????"?"?????????queryString
                request.queryString().setBytes(byteBuffer.array(), parsingRequestLineQPos + 1,
                        end - parsingRequestLineQPos - 1);
                // ??????URI
                request.requestURI().setBytes(byteBuffer.array(), parsingRequestLineStart,
                        parsingRequestLineQPos - parsingRequestLineStart);
            } else {
                // ??????queryString????????????URI
                request.requestURI().setBytes(byteBuffer.array(), parsingRequestLineStart,
                        end - parsingRequestLineStart);
            }
            // HTTP/0.9 processing jumps to stage 7.
            // Don't want to overwrite that here.
            if (parsingRequestLinePhase == 4) {
                parsingRequestLinePhase = 5;
            }
        }
        /**
         * ??????5?????????????????????????????????????????????????????????parsingRequestLineStart??????
         */
        if (parsingRequestLinePhase == 5) {
            // Spec says single SP but also be tolerant of multiple and/or HT
            boolean space = true;
            while (space) {
                // Read new bytes if needed
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (!fill(false)) // request line parsing
                        return false;
                }
                byte chr = byteBuffer.get();
                if (!(chr == Constants.SP || chr == Constants.HT)) {
                    space = false;
                    byteBuffer.position(byteBuffer.position() - 1);
                }
            }
            parsingRequestLineStart = byteBuffer.position();
            parsingRequestLinePhase = 6;

            // Mark the current buffer position
            end = 0;
        }
        /**
         * ??????6??????????????????????????????HTTP/x.x   ???x??????????????????
         */
        if (parsingRequestLinePhase == 6) {
            //
            // Reading the protocol
            // Protocol is always "HTTP/" DIGIT "." DIGIT
            //
            while (!parsingRequestLineEol) {
                // Read new bytes if needed
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (!fill(false)) // request line parsing
                        return false;
                }

                int pos = byteBuffer.position();
                prevChr = chr;
                chr = byteBuffer.get();
                if (chr == Constants.CR) {
                    // Possible end of request line. Need LF next.
                } else if (prevChr == Constants.CR && chr == Constants.LF) {
                    end = pos - 1;
                    parsingRequestLineEol = true;
                } else if (!HttpParser.isHttpProtocol(chr)) {
                    String invalidProtocol = parseInvalid(parsingRequestLineStart, byteBuffer);
                    throw new IllegalArgumentException(sm.getString("iib.invalidHttpProtocol", invalidProtocol));
                }
            }

            if ((end - parsingRequestLineStart) > 0) {
                // ????????????
                request.protocol().setBytes(byteBuffer.array(), parsingRequestLineStart,
                        end - parsingRequestLineStart);
                parsingRequestLinePhase = 7;
            }
            // If no protocol is found, the ISE below will be triggered.
        }
        /**
         * ??????7????????????????????????????????????
         */
        if (parsingRequestLinePhase == 7) {
            // Parsing is complete. Return and clean-up.
            parsingRequestLine = false;
            parsingRequestLinePhase = 0;
            parsingRequestLineEol = false;
            parsingRequestLineStart = 0;
            return true;
        }
        throw new IllegalStateException(sm.getString("iib.invalidPhase", Integer.valueOf(parsingRequestLinePhase)));
    }


    /**
     * Parse the HTTP headers.
     */
    boolean parseHeaders() throws IOException {
        if (!parsingHeader) {
            throw new IllegalStateException(sm.getString("iib.parseheaders.ise.error"));
        }

        HeaderParseStatus status = HeaderParseStatus.HAVE_MORE_HEADERS;

        /**
         * ?????????????????????header????????????????????????
         */
        do {
            status = parseHeader();
            // Checking that
            // (1) Headers plus request line size does not exceed its limit
            // (2) There are enough bytes to avoid expanding the buffer when reading body
            // Technically, (2) is technical limitation, (1) is logical
            // limitation to enforce the meaning of headerBufferSize
            // From the way how buf is allocated and how blank lines are being
            // read, it should be enough to check (1) only.
            /*
             * ?????????
             * ???1???header???requestLine???????????????????????????
             * ???2?????????body????????????????????????????????????????????????
             * ?????????????????????2????????????????????????1??????????????????
             * ????????????headerBufferSize??????????????????????????????buf??????????????????????????????????????????????????????1??????????????????
             */
            // ??????8K???????????????????????????header too large??????
            if (byteBuffer.position() > headerBufferSize || byteBuffer.capacity() - byteBuffer.position() < socketReadBufferSize) {
                throw new IllegalArgumentException(sm.getString("iib.requestheadertoolarge.error"));
            }
        } while (status == HeaderParseStatus.HAVE_MORE_HEADERS);
        if (status == HeaderParseStatus.DONE) {
            parsingHeader = false;
            end = byteBuffer.position();
            return true;
        } else {
            return false;
        }
    }


    int getParsingRequestLinePhase() {
        return parsingRequestLinePhase;
    }


    private String parseInvalid(int startPos, ByteBuffer buffer) {
        // Look for the next space
        byte b = 0;
        while (buffer.hasRemaining() && b != 0x20) {
            b = buffer.get();
        }
        String result = HeaderUtil.toPrintableString(buffer.array(), buffer.arrayOffset() + startPos, buffer.position() - startPos - 1);
        if (b != 0x20) {
            // Ran out of buffer rather than found a space
            result = result + "...";
        }
        return result;
    }


    /**
     * End request (consumes leftover bytes).
     *
     * @throws IOException an underlying I/O error occurred
     */
    void endRequest() throws IOException {

        if (swallowInput && (lastActiveFilter != -1)) {
            int extraBytes = (int) activeFilters[lastActiveFilter].end();
            byteBuffer.position(byteBuffer.position() - extraBytes);
        }
    }


    /**
     * Available bytes in the buffers (note that due to encoding, this may not
     * correspond).
     */
    int available(boolean read) {
        int available = byteBuffer.remaining();
        if ((available == 0) && (lastActiveFilter >= 0)) {
            for (int i = 0; (available == 0) && (i <= lastActiveFilter); i++) {
                available = activeFilters[i].available();
            }
        }
        if (available > 0 || !read) {
            return available;
        }

        try {
            if (wrapper.hasDataToRead()) {
                fill(false);
                available = byteBuffer.remaining();
            }
        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("iib.available.readFail"), ioe);
            }
            // Not ideal. This will indicate that data is available which should
            // trigger a read which in turn will trigger another IOException and
            // that one can be thrown.
            available = 1;
        }
        return available;
    }


    /**
     * Has all of the request body been read? There are subtle differences
     * between this and available() &gt; 0 primarily because of having to handle
     * faking non-blocking reads with the blocking IO connector.
     */
    boolean isFinished() {
        if (byteBuffer.limit() > byteBuffer.position()) {
            // Data to read in the buffer so not finished
            return false;
        }

        /*
         * Don't use fill(false) here because in the following circumstances
         * BIO will block - possibly indefinitely
         * - client is using keep-alive and connection is still open
         * - client has sent the complete request
         * - client has not sent any of the next request (i.e. no pipelining)
         * - application has read the complete request
         */

        // Check the InputFilters

        if (lastActiveFilter >= 0) {
            return activeFilters[lastActiveFilter].isFinished();
        } else {
            // No filters. Assume request is not finished. EOF will signal end of
            // request.
            return false;
        }
    }

    ByteBuffer getLeftover() {
        int available = byteBuffer.remaining();
        if (available > 0) {
            return ByteBuffer.wrap(byteBuffer.array(), byteBuffer.position(), available);
        } else {
            return null;
        }
    }


    boolean isChunking() {
        for (int i = 0; i < lastActiveFilter; i++) {
            if (activeFilters[i] == filterLibrary[Constants.CHUNKED_FILTER]) {
                return true;
            }
        }
        return false;
    }


    void init(SocketWrapperBase<?> socketWrapper) {

        wrapper = socketWrapper;
        wrapper.setAppReadBufHandler(this);

        int bufLength = headerBufferSize +
                wrapper.getSocketBufferHandler().getReadBuffer().capacity();
        if (byteBuffer == null || byteBuffer.capacity() < bufLength) {
            byteBuffer = ByteBuffer.allocate(bufLength);
            byteBuffer.position(0).limit(0);
        }
    }



    // --------------------------------------------------------- Private Methods

    /**
     * Attempts to read some data into the input buffer.
     *
     * @return <code>true</code> if more data was added to the input buffer
     *         otherwise <code>false</code>
     */
    /**
     * ?????????????????????????????????????????????
     *
     * @param block
     * @return ??????????????????????????????????????????????????????true????????????false
     * @throws IOException
     */
    private boolean fill(boolean block) throws IOException {

        if (parsingHeader) {
            // ??????????????????8*1024?????????8K????????????????????????????????????
            // ????????????Header?????????8K??????
            if (byteBuffer.limit() >= headerBufferSize) {
                if (parsingRequestLine) {
                    // Avoid unknown protocol triggering an additional error  ??????????????????????????????????????????
                    request.protocol().setString(Constants.HTTP_11);
                }
                // header???????????????
                throw new IllegalArgumentException(sm.getString("iib.requestheadertoolarge.error"));
            }
        } else {
            // ????????????limit???position
            byteBuffer.limit(end).position(end);
        }

        // ?????????????????????position
        byteBuffer.mark();
        if (byteBuffer.position() < byteBuffer.limit()) {
            byteBuffer.position(byteBuffer.limit());
        }
        byteBuffer.limit(byteBuffer.capacity());
        SocketWrapperBase<?> socketWrapper = this.wrapper;
        int nRead = -1;
        if (socketWrapper != null) {
            // ??? socket ??????????????? byteBuffer ???
            nRead = socketWrapper.read(block, byteBuffer);
        } else {
            throw new CloseNowException(sm.getString("iib.eof.error"));
        }
        byteBuffer.limit(byteBuffer.position()).reset();
        if (nRead > 0) {
            return true;
        } else if (nRead == -1) {
            throw new EOFException(sm.getString("iib.eof.error"));
        } else {
            return false;
        }

    }


    /**
     * Parse an HTTP header.
     *
     * @return false after reading a blank line (which indicates that the HTTP header parsing is done
     */
    /**
     * ????????????HTTP header
     *
     * @return ?????????????????????false?????????HTTP header???????????????
     */
    private HeaderParseStatus parseHeader() throws IOException {

        while (headerParsePos == HeaderParsePosition.HEADER_START) {

            // Read new bytes if needed?????????????????????????????????????????????????????????fill?????????
            if (byteBuffer.position() >= byteBuffer.limit()) {  // ????????????????????????????????????????????????????????????????????????
                if (!fill(false)) {// parse header
                    headerParsePos = HeaderParsePosition.HEADER_START;
                    // ??????????????????????????????
                    return HeaderParseStatus.NEED_MORE_DATA;
                }
            }

            prevChr = chr;
            chr = byteBuffer.get();

            if (chr == Constants.CR && prevChr != Constants.CR) {
                // Possible start of CRLF - process the next byte.  (CRLF???????????????-????????????????????????)
            } else if (prevChr == Constants.CR && chr == Constants.LF) {
                // ?????????/r/n???header????????????
                return HeaderParseStatus.DONE;
            } else {
                if (prevChr == Constants.CR) {
                    // Must have read two bytes (first was CR, second was not LF)
                    // ??????????????????????????????????????????\r??????????????????\n???
                    byteBuffer.position(byteBuffer.position() - 2);
                } else {
                    // Must have only read one byte (???????????????????????????)
                    byteBuffer.position(byteBuffer.position() - 1);
                }
                break;
            }
        }

        if (headerParsePos == HeaderParsePosition.HEADER_START) {
            // Mark the current buffer position (mark?????????????????????)
            headerData.start = byteBuffer.position();
            headerData.lineStart = headerData.start;
            headerParsePos = HeaderParsePosition.HEADER_NAME;
        }

        //
        // Reading the header name          (??????header??????)
        // Header name is always US-ASCII   (header????????????US-ASCII)
        //

        while (headerParsePos == HeaderParsePosition.HEADER_NAME) {

            // Read new bytes if needed
            if (byteBuffer.position() >= byteBuffer.limit()) {
                if (!fill(false)) { // parse header
                    return HeaderParseStatus.NEED_MORE_DATA;
                }
            }

            int pos = byteBuffer.position();
            chr = byteBuffer.get();
            if (chr == Constants.COLON) {  // ?????????":"??????
                headerParsePos = HeaderParsePosition.HEADER_VALUE_START;
                /**
                 * ??????header??????
                 */
                headerData.headerValue = headers.addValue(byteBuffer.array(), headerData.start,
                        pos - headerData.start);
                pos = byteBuffer.position();
                // Mark the current buffer position
                headerData.start = pos;
                headerData.realPos = pos;
                headerData.lastSignificantChar = pos;
                break;
            } else if (!HttpParser.isToken(chr)) {
                // Non-token characters are illegal in header names
                // Parsing continues so the error can be reported in context
                headerData.lastSignificantChar = pos;
                byteBuffer.position(byteBuffer.position() - 1);
                // skipLine() will handle the error
                return skipLine();
            }

            // chr is next byte of header name. Convert to lowercase.
            if ((chr >= Constants.A) && (chr <= Constants.Z)) {
                byteBuffer.put(pos, (byte) (chr - Constants.LC_OFFSET));
            }
        }

        // Skip the line and ignore the header
        if (headerParsePos == HeaderParsePosition.HEADER_SKIPLINE) {
            return skipLine();
        }

        //
        // Reading the header value (which can be spanned over multiple lines)
        //

        while (headerParsePos == HeaderParsePosition.HEADER_VALUE_START ||
               headerParsePos == HeaderParsePosition.HEADER_VALUE ||
               headerParsePos == HeaderParsePosition.HEADER_MULTI_LINE) {

            if (headerParsePos == HeaderParsePosition.HEADER_VALUE_START) {
                // Skipping spaces
                while (true) {
                    // Read new bytes if needed
                    if (byteBuffer.position() >= byteBuffer.limit()) {
                        if (!fill(false)) {// parse header
                            // HEADER_VALUE_START
                            return HeaderParseStatus.NEED_MORE_DATA;
                        }
                    }

                    chr = byteBuffer.get();
                    if (!(chr == Constants.SP || chr == Constants.HT)) {
                        headerParsePos = HeaderParsePosition.HEADER_VALUE;
                        byteBuffer.position(byteBuffer.position() - 1);
                        break;
                    }
                }
            }
            if (headerParsePos == HeaderParsePosition.HEADER_VALUE) {

                // Reading bytes until the end of the line
                boolean eol = false;
                while (!eol) {

                    // Read new bytes if needed
                    if (byteBuffer.position() >= byteBuffer.limit()) {
                        if (!fill(false)) {// parse header
                            // HEADER_VALUE
                            return HeaderParseStatus.NEED_MORE_DATA;
                        }
                    }

                    prevChr = chr;
                    chr = byteBuffer.get();
                    if (chr == Constants.CR) {
                        // Possible start of CRLF - process the next byte.
                    } else if (prevChr == Constants.CR && chr == Constants.LF) {
                        eol = true;
                    } else if (prevChr == Constants.CR) {
                        // Invalid value
                        // Delete the header (it will be the most recent one)
                        headers.removeHeader(headers.size() - 1);
                        return skipLine();
                    } else if (chr != Constants.HT && HttpParser.isControl(chr)) {
                        // Invalid value
                        // Delete the header (it will be the most recent one)
                        headers.removeHeader(headers.size() - 1);
                        return skipLine();
                    } else if (chr == Constants.SP || chr == Constants.HT) {
                        byteBuffer.put(headerData.realPos, chr);
                        headerData.realPos++;
                    } else {
                        byteBuffer.put(headerData.realPos, chr);
                        headerData.realPos++;
                        headerData.lastSignificantChar = headerData.realPos;
                    }
                }

                // Ignore whitespaces at the end of the line
                headerData.realPos = headerData.lastSignificantChar;

                // Checking the first character of the new line. If the character
                // is a LWS, then it's a multiline header
                headerParsePos = HeaderParsePosition.HEADER_MULTI_LINE;
            }
            // Read new bytes if needed
            if (byteBuffer.position() >= byteBuffer.limit()) {
                if (!fill(false)) {// parse header
                    // HEADER_MULTI_LINE
                    return HeaderParseStatus.NEED_MORE_DATA;
                }
            }

            byte peek = byteBuffer.get(byteBuffer.position());
            if (headerParsePos == HeaderParsePosition.HEADER_MULTI_LINE) {
                if ((peek != Constants.SP) && (peek != Constants.HT)) {
                    headerParsePos = HeaderParsePosition.HEADER_START;
                    break;
                } else {
                    // Copying one extra space in the buffer (since there must be at least one space inserted between the lines)
                    // ????????????????????????????????????????????????????????????????????????????????????????????????
                    byteBuffer.put(headerData.realPos, peek);
                    headerData.realPos++;
                    headerParsePos = HeaderParsePosition.HEADER_VALUE_START;
                }
            }
        }
        /**
         * ??????header???value
         */
        // Set the header value
        headerData.headerValue.setBytes(byteBuffer.array(), headerData.start,
                headerData.lastSignificantChar - headerData.start);
        headerData.recycle();
        return HeaderParseStatus.HAVE_MORE_HEADERS;
    }


    private HeaderParseStatus skipLine() throws IOException {
        headerParsePos = HeaderParsePosition.HEADER_SKIPLINE;
        boolean eol = false;

        // Reading bytes until the end of the line
        while (!eol) {

            // Read new bytes if needed
            if (byteBuffer.position() >= byteBuffer.limit()) {
                if (!fill(false)) {
                    return HeaderParseStatus.NEED_MORE_DATA;
                }
            }

            int pos = byteBuffer.position();
            prevChr = chr;
            chr = byteBuffer.get();
            if (chr == Constants.CR) {
                // Skip
            } else if (prevChr == Constants.CR && chr == Constants.LF) {
                eol = true;
            } else {
                headerData.lastSignificantChar = pos;
            }
        }
        if (rejectIllegalHeader || log.isDebugEnabled()) {
            String message = sm.getString("iib.invalidheader",
                    HeaderUtil.toPrintableString(byteBuffer.array(), headerData.lineStart,
                            headerData.lastSignificantChar - headerData.lineStart + 1));
            if (rejectIllegalHeader) {
                throw new IllegalArgumentException(message);
            }
            log.debug(message);
        }

        headerParsePos = HeaderParsePosition.HEADER_START;
        return HeaderParseStatus.HAVE_MORE_HEADERS;
    }


    // ----------------------------------------------------------- Inner classes

    private enum HeaderParseStatus {
        DONE, HAVE_MORE_HEADERS, NEED_MORE_DATA
    }


    private enum HeaderParsePosition {
        /**
         * Start of a new header. A CRLF here means that there are no more
         * headers. Any other character starts a header name.
         */
        HEADER_START,
        /**
         * Reading a header name. All characters of header are HTTP_TOKEN_CHAR.
         * Header name is followed by ':'. No whitespace is allowed.<br>
         * Any non-HTTP_TOKEN_CHAR (this includes any whitespace) encountered
         * before ':' will result in the whole line being ignored.
         */
        HEADER_NAME,
        /**
         * Skipping whitespace before text of header value starts, either on the
         * first line of header value (just after ':') or on subsequent lines
         * when it is known that subsequent line starts with SP or HT.
         */
        HEADER_VALUE_START,
        /**
         * Reading the header value. We are inside the value. Either on the
         * first line or on any subsequent line. We come into this state from
         * HEADER_VALUE_START after the first non-SP/non-HT byte is encountered
         * on the line.
         */
        HEADER_VALUE,
        /**
         * Before reading a new line of a header. Once the next byte is peeked,
         * the state changes without advancing our position. The state becomes
         * either HEADER_VALUE_START (if that first byte is SP or HT), or
         * HEADER_START (otherwise).
         */
        HEADER_MULTI_LINE,
        /**
         * Reading all bytes until the next CRLF. The line is being ignored.
         */
        HEADER_SKIPLINE
    }


    private static class HeaderParseData {
        /**
         * The first character of the header line.
         */
        int lineStart = 0;
        /**
         * When parsing header name: first character of the header.<br>
         * When skipping broken header line: first character of the header.<br>
         * When parsing header value: first character after ':'.
         */
        int start = 0;
        /**
         * When parsing header name: not used (stays as 0).<br>
         * When skipping broken header line: not used (stays as 0).<br>
         * When parsing header value: starts as the first character after ':'.
         * Then is increased as far as more bytes of the header are harvested.
         * Bytes from buf[pos] are copied to buf[realPos]. Thus the string from
         * [start] to [realPos-1] is the prepared value of the header, with
         * whitespaces removed as needed.<br>
         */
        int realPos = 0;
        /**
         * When parsing header name: not used (stays as 0).<br>
         * When skipping broken header line: last non-CR/non-LF character.<br>
         * When parsing header value: position after the last not-LWS character.<br>
         */
        int lastSignificantChar = 0;
        /**
         * MB that will store the value of the header. It is null while parsing
         * header name and is created after the name has been parsed.
         */
        MessageBytes headerValue = null;
        public void recycle() {
            lineStart = 0;
            start = 0;
            realPos = 0;
            lastSignificantChar = 0;
            headerValue = null;
        }
    }


    // ------------------------------------- InputStreamInputBuffer Inner Class

    /**
     * This class is an input buffer which will read its data from an input
     * stream.
     */
    private class SocketInputBuffer implements InputBuffer {

        @Override
        public int doRead(ApplicationBufferHandler handler) throws IOException {

            if (byteBuffer.position() >= byteBuffer.limit()) {
                // The application is reading the HTTP request body which is
                // always a blocking operation.
                if (!fill(true))
                    return -1;
            }

            int length = byteBuffer.remaining();
            handler.setByteBuffer(byteBuffer.duplicate());
            byteBuffer.position(byteBuffer.limit());

            return length;
        }
    }


    @Override
    public void setByteBuffer(ByteBuffer buffer) {
        byteBuffer = buffer;
    }


    @Override
    public ByteBuffer getByteBuffer() {
        return byteBuffer;
    }


    @Override
    public void expand(int size) {
        if (byteBuffer.capacity() >= size) {
            byteBuffer.limit(size);
        }
        ByteBuffer temp = ByteBuffer.allocate(size);
        temp.put(byteBuffer);
        byteBuffer = temp;
        byteBuffer.mark();
        temp = null;
    }
}
