package com.soundcloud.api;

import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MIME;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.AbstractContentBody;
import org.apache.http.entity.mime.content.ContentBody;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.message.BasicNameValuePair;
import org.apache.james.mime4j.util.CharsetUtil;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Convenience class for constructing HTTP requests.
 *
 * Example:
 * <code>
 *   <pre>
 *  HttpRequest request = Request.to("/tracks")
 *     .with("track[user]", 1234)
 *     .withFile("track[asset_data]", new File("track.mp3")
 *     .buildRequest(HttpPost.class);
 *
 *  httpClient.execute(request);
 *   </pre>
 *  </code>
 */
public class Request implements Iterable<NameValuePair> {
    private List<NameValuePair> mParams = new ArrayList<NameValuePair>(); // XXX should probably be lazy
    private Map<String, Attachment> mFiles;

    private HttpEntity mEntity;

    private Token mToken;
    private String mResource;
    private TransferProgressListener listener;
    private String mIfNoneMatch;

    /** Empty request */
    public Request() {}

    /**
     * @param resource the base resource
     */
    public Request(String resource) {
        if (resource != null && resource.contains("?")) {
            String query = resource.substring(Math.min(resource.length(), resource.indexOf("?")+1),
                    resource.length());
            for (String s : query.split("&")) {
                String[] kv = s.split("=", 2);
                if (kv != null && kv.length == 2) {
                    try {
                        mParams.add(new BasicNameValuePair(
                                URLDecoder.decode(kv[0], "UTF-8"),
                                URLDecoder.decode(kv[1], "UTF-8")));
                    } catch (UnsupportedEncodingException ignored) {}
                }
            }
            mResource = resource.substring(0, resource.indexOf("?"));
        } else {
            mResource = resource;
        }
    }

    /**
     * constructs a a request from URI. the hostname+scheme will be ignored
     * @param uri - the uri
     */
    public Request(URI uri) {
        this(uri.getPath() == null ? "/" : uri.getPath() +
            (uri.getQuery() == null ? "" : "?"+uri.getQuery()));
    }

    /**
     * @param request the request to be copied
     */
    public Request(Request request) {
        mResource = request.mResource;
        mToken = request.mToken;
        listener = request.listener;
        mParams = new ArrayList<NameValuePair>(request.mParams);
        mIfNoneMatch = request.mIfNoneMatch;
        if (request.mFiles != null) mFiles = new HashMap<String, Attachment>(request.mFiles);
    }

    /**
     * @param resource  the resource to request
     * @param args      optional string expansion arguments (passed to String#format(String, Object...)
     * @throws java.util.IllegalFormatException - If a format string contains an illegal syntax,
     * @return the request
     * @see String#format(String, Object...)
     */
    public static Request to(String resource, Object... args) {
        if (args != null &&
            args.length > 0) {
            resource = String.format(resource, args);
        }
        return new Request(resource);
    }

    /**
     * Adds a key value pair
     * @param name  the name
     * @param value the value
     * @return this
     */
    public Request add(String name, Object value) {
        mParams.add(new BasicNameValuePair(name, String.valueOf(value)));
        return this;
    }

    /**
     * @param args a list of arguments
     * @return this
     */
    public Request with(Object... args) {
       if (args != null) {
            if (args.length % 2 != 0) throw new IllegalArgumentException("need even number of arguments");
            for (int i = 0; i < args.length; i += 2) {
                add(args[i].toString(), args[i + 1]);
            }
       }
       return this;
    }

    /**
     * The request should be made with a specific token.
     * @param token the token
     * @return this
     */
    public Request usingToken(Token token) {
        mToken = token;
        return this;
    }

    /** @return the size of the parameters */
    public int size() {
        return mParams.size();
    }

    /**
     * @return a String that is suitable for use as an <code>application/x-www-form-urlencoded</code>
     * list of parameters in an HTTP PUT or HTTP POST.
     */
    public String queryString() {
        return URLEncodedUtils.format(mParams, "UTF-8");
    }

    /**
     * @param  resource the resource
     * @return an URL with the query string parameters appended
     */
    public String toUrl(String resource) {
        return mParams.isEmpty() ? resource : resource + "?" + queryString();
    }

    public String toUrl() {
        return toUrl(mResource);
    }

    /**
     * Registers a file to be uploaded with a POST or PUT request.
     * @param name  the name of the parameter
     * @param file  the file to be submitted
     * @return this
     */
    public Request withFile(String name, File file) {
        return withFile(name, file, file.getName());
    }

    /**
     * Registers a file to be uploaded with a POST or PUT request.
     * @param name  the name of the parameter
     * @param file  the file to be submitted
     * @param fileName  the name of the uploaded file (overrides file parameter)
     * @return this
     */
    public Request withFile(String name, File file, String fileName) {
        if (mFiles == null) mFiles = new HashMap<String,Attachment>();
        if (file != null)  mFiles.put(name, new Attachment(file, fileName));
        return this;
    }

    /**
     * Registers binary data to be uploaded with a POST or PUT request.
     * @param name  the name of the parameter
     * @param data  the data to be submitted
     * @deprecated use {@link #withFile(String, byte[], String)} instead
     * @return this
     */
    public Request withFile(String name, byte[] data) {
        return withFile(name, ByteBuffer.wrap(data));
    }

    /**
     * Registers binary data to be uploaded with a POST or PUT request.
     * @param name  the name of the parameter
     * @param data  the data to be submitted
     * @param fileName the name of the uploaded file
     * @return this
     */
    public Request withFile(String name, byte[] data, String fileName) {
        return withFile(name, ByteBuffer.wrap(data), fileName);
    }

    /**
     * Registers binary data to be uploaded with a POST or PUT request.
     * @param name  the name of the parameter
     * @param data  the data to be submitted
     * @return this
     * @deprecated use {@link #withFile(String, java.nio.ByteBuffer), String} instead
     */
    public Request withFile(String name, ByteBuffer data) {
        return withFile(name, data, "upload");
    }


    /**
     * Registers binary data to be uploaded with a POST or PUT request.
     * @param name  the name of the parameter
     * @param data  the data to be submitted
     * @param fileName the name of the uploaded file
     * @return this
     */
    public Request withFile(String name, ByteBuffer data, String fileName) {
        if (mFiles == null) mFiles = new HashMap<String, Attachment>();
        if (data != null) mFiles.put(name, new Attachment(data, fileName));
        return this;
    }

    /**
     * Adds an arbitrary entity to the request (used with POST/PUT)
     * @param entity the entity to POST/PUT
     * @return this
     */
    public Request withEntity(HttpEntity entity) {
        mEntity = entity;
        return this;
    }

    /**
     * Adds string content to the request (used with POST/PUT)
     * @param content the content to POST/PUT
     * @param contentType the content type
     * @return this
     */
    public Request withContent(String content, String contentType) {
        try {
            StringEntity stringEntity = new StringEntity(content);
            if (contentType != null) {
                stringEntity.setContentType(contentType);
            }
            return withEntity(stringEntity);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param listener a listener for receiving notifications about transfer progress
     * @return this
     */
    public Request setProgressListener(TransferProgressListener listener) {
        this.listener = listener;
        return this;
    }

    public boolean isMultipart() {
        return mFiles != null && !mFiles.isEmpty();
    }

    /**
     * Conditional GET
     * @param etag the etag to check for (If-None-Match: etag)
     * @return this
     */
    public Request ifNoneMatch(String etag) {
        mIfNoneMatch = etag;
        return this;
    }

    /**
     * Builds a request with the given set of parameters and files.
     * @param method    the type of request to use
     * @param <T>       the type of request to use
     * @return HTTP request, prepared to be executed
     */
    public <T extends HttpRequestBase> T buildRequest(Class<T> method) {
        try {
            T request = method.newInstance();
            // POST/PUT ?
            if (request instanceof HttpEntityEnclosingRequestBase) {
                HttpEntityEnclosingRequestBase enclosingRequest =
                        (HttpEntityEnclosingRequestBase) request;

                final Charset charSet =  CharsetUtil.getCharset("UTF-8");
                if (isMultipart()) {
                    MultipartEntity multiPart = new MultipartEntity(
                            HttpMultipartMode.BROWSER_COMPATIBLE,  // XXX change this to STRICT once rack on server is upgraded
                            null,
                            charSet);

                    if (mFiles != null) {
                        for (Map.Entry<String, Attachment> e : mFiles.entrySet()) {
                            multiPart.addPart(e.getKey(), e.getValue().toContentBody());
                        }
                    }

                    for (NameValuePair pair : mParams) {
                        multiPart.addPart(pair.getName(), new StringBody(pair.getValue(), "text/plain", charSet));
                    }

                    enclosingRequest.setEntity(listener == null ? multiPart :
                        new CountingMultipartEntity(multiPart, listener));
                // form-urlencoded?
                } else if (!mParams.isEmpty()) {
                    request.setHeader("Content-Type", "application/x-www-form-urlencoded");
                    enclosingRequest.setEntity(new StringEntity(queryString()));
                } else if (mEntity != null) {
                    request.setHeader(mEntity.getContentType());
                    enclosingRequest.setEntity(mEntity);
                }

                request.setURI(URI.create(mResource));
            } else { // just plain GET/DELETE/...
                if (mIfNoneMatch != null) {
                    request.addHeader("If-None-Match", mIfNoneMatch);
                }
                request.setURI(URI.create(toUrl()));
            }

            if (mToken != null) {
                request.addHeader(ApiWrapper.createOAuthHeader(mToken));
            }
            return request;
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (UnsupportedEncodingException e) {
            // XXX really rethrow?
            throw new RuntimeException(e);
        }
    }

    @Override public Iterator<NameValuePair> iterator() {
        return mParams.iterator();
    }

    @Override
    public String toString() {
        return "Request{" +
                 "mResource='" + mResource + '\'' +
                ", params=" + mParams +
                ", files=" + mFiles +
                ", entity=" + mEntity +
                ", mToken=" + mToken +
                ", listener=" + listener +
                '}';
    }

    /* package */ Token getToken() {
        return mToken;
    }

    /* package */ TransferProgressListener getListener() {
        return listener;
    }


    /**
     * Updates about the amount of bytes already transferred.
     */
    public static interface TransferProgressListener {
        /**
         * @param amount number of bytes already transferred.
         * @throws IOException if the transfer should be cancelled
         */
        public void transferred(long amount) throws IOException;
    }



    static class ByteBufferBody extends AbstractContentBody {
        private ByteBuffer mBuffer;

        public ByteBufferBody(ByteBuffer buffer) {
            super("application/octet-stream");
            mBuffer = buffer;
        }

        @Override
        public String getFilename() {
            return null;
        }

        public String getTransferEncoding() {
            return MIME.ENC_BINARY;
        }

        public String getCharset() {
            return null;
        }

        @Override
        public long getContentLength() {
            return mBuffer.capacity();
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            if (mBuffer.hasArray()) {
                out.write(mBuffer.array());
            } else {
                byte[] dst = new byte[mBuffer.capacity()];
                mBuffer.get(dst);
                out.write(dst);
            }
        }
    }

    /* package */ static class Attachment {
        public final File file;
        public final ByteBuffer data;
        public final String fileName;

        /** @noinspection UnusedDeclaration*/
        Attachment(File file) {
            this(file, file.getName());
        }

        Attachment(File file, String fileName) {
            if (file == null) throw  new IllegalArgumentException("file cannot be null");
            this.fileName = fileName;
            this.file = file;
            this.data = null;
        }

        /** @noinspection UnusedDeclaration*/
        Attachment(ByteBuffer data) {
            this(data, null);
        }

        Attachment(ByteBuffer data, String fileName) {
            if (data == null) throw new IllegalArgumentException("data cannot be null");

            this.data = data;
            this.fileName = fileName;
            this.file = null;
        }

        public ContentBody toContentBody() {
            if (file != null) {
                return new FileBody(file) {
                    @Override
                    public String getFilename() {
                        return fileName;
                    }
                };
            } else if (data != null) {
                return new ByteBufferBody(data) {
                    @Override
                    public String getFilename() {
                        return fileName;
                    }
                };
            } else {
                // never happens
                throw new IllegalStateException("no upload data");
            }
        }
    }
}