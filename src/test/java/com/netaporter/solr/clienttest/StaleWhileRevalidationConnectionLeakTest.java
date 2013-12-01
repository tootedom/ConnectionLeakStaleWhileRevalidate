package com.netaporter.solr.clienttest;

import com.xebialabs.restito.server.StubServer;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.cache.CacheResponseStatus;
import org.apache.http.client.cache.HttpCacheContext;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.cache.CacheConfig;
import org.apache.http.impl.client.cache.CachingHttpClientBuilder;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static com.xebialabs.restito.builder.stub.StubHttp.whenHttp;
import static com.xebialabs.restito.semantics.Action.*;
import static com.xebialabs.restito.semantics.Condition.get;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class StaleWhileRevalidationConnectionLeakTest {

    private StubServer server;
    private int port;
    private CloseableHttpClient client;
    private final String url = "/static/dom";
    private final String url2 = "2";

    @Before
    public void start() {
        server = new StubServer().run();
        port = server.getPort();

        CacheConfig cacheConfig = CacheConfig.custom()
                .setMaxCacheEntries(100)
                .setMaxObjectSize(15) //1574
                .setAsynchronousWorkerIdleLifetimeSecs(60)
                .setAsynchronousWorkersMax(1)
                .setAsynchronousWorkersCore(1)
                .setRevalidationQueueSize(100)
                .setSharedCache(true)
                .build();

        HttpClientBuilder clientBuilder = CachingHttpClientBuilder.create().setCacheConfig(cacheConfig);
        clientBuilder.setMaxConnTotal(1);
        clientBuilder.setMaxConnPerRoute(1);

        RequestConfig config = RequestConfig.custom()
                .setSocketTimeout(1000)
                .setConnectTimeout(1000)
                .setConnectionRequestTimeout(1000)
                .build();

        clientBuilder.setDefaultRequestConfig(config);


        client = clientBuilder.build();
    }

    @After
    public void stop() {
        server.stop();
        try {
            client.close();
        } catch(IOException e) {
            e.printStackTrace();
        }
    }


    @Test
    public void testStaleWhileRevalidate() {
        // Restito
        whenHttp(server).
                match(get(url)).
                then(status(HttpStatus.OK_200), header("Cache-Control", "public, max-age=3, stale-while-revalidate=5"),
                        stringContent("sdgsdgffgfgdom"));

        whenHttp(server).
                match(get(url + url2)).
                then(status(HttpStatus.OK_200), header("Cache-Control", "public, max-age=3, stale-while-revalidate=5"),
                        stringContent("sdgsdgffgfgdom"));

        HttpContext localContext = new BasicHttpContext();
        Exception requestException = null;

        // This will fetch from backend.
        requestException = sendRequest(client, localContext,port);
        assertNull(requestException);

        CacheResponseStatus responseStatus = (CacheResponseStatus) localContext.getAttribute(HttpCacheContext.CACHE_RESPONSE_STATUS);
        assertEquals(CacheResponseStatus.CACHE_MISS,responseStatus);

        try {
            Thread.sleep(1000);
        } catch (Exception e) {

        }
        // These will be cached
        requestException = sendRequest(client, localContext,port);
        assertNull(requestException);

        responseStatus = (CacheResponseStatus) localContext.getAttribute(HttpCacheContext.CACHE_RESPONSE_STATUS);
        assertEquals(CacheResponseStatus.CACHE_HIT,responseStatus);

        requestException = sendRequest(client, localContext,port);
        assertNull(requestException);

        responseStatus = (CacheResponseStatus) localContext.getAttribute(HttpCacheContext.CACHE_RESPONSE_STATUS);
        assertEquals(CacheResponseStatus.CACHE_HIT,responseStatus);

        // wait, so that max-age is expired
        try {
            Thread.sleep(4000);
        } catch (Exception e) {

        }

        whenHttp(server).
                match(get(url)).
                then(status(HttpStatus.OK_200), header("Cache-Control", "public, max-age=3, stale-while-revalidate=5"),
                        stringContent("This is new content that is bigger than cache limit"));

        // This will cause a revalidation to occur
        requestException = sendRequest(client, localContext,port);
        assertNull(requestException);

        responseStatus = (CacheResponseStatus) localContext.getAttribute(HttpCacheContext.CACHE_RESPONSE_STATUS);
        assertEquals(CacheResponseStatus.CACHE_HIT,responseStatus);

        try {
            Thread.sleep(1000);
        } catch (Exception e) {

        }

        // fetch a different content This will hang due to connection leak in revalidation
        requestException = sendRequest(client, localContext,port, url2);
        if(requestException!=null) {
            requestException.printStackTrace();
        }
        assertNull(requestException);


    }

    static Exception sendRequest(HttpClient cachingClient, HttpContext localContext, int port) {
        return sendRequest(cachingClient, localContext,port,"");
    }

    static Exception sendRequest(HttpClient cachingClient, HttpContext localContext , int port,String extra) {
        HttpGet httpget = new HttpGet("http://localhost:"+port+"/static/dom"+extra);
        HttpResponse response = null;
        try {
            response = cachingClient.execute(httpget, localContext);
            return null;
        } catch (ClientProtocolException e1) {
            return e1;
        } catch (IOException e1) {
            return e1;
        } finally {
            if(response!=null) {
                HttpEntity entity = response.getEntity();
                try {
                    EntityUtils.consume(entity);
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
    }

    static void checkResponse(CacheResponseStatus responseStatus) {
        switch (responseStatus) {
            case CACHE_HIT:
                System.out.println("A response was generated from the cache with no requests "
                        + "sent upstream");
                break;
            case CACHE_MODULE_RESPONSE:
                System.out.println("The response was generated directly by the caching module");
                break;
            case CACHE_MISS:
                System.out.println("The response came from an upstream server");
                break;
            case VALIDATED:
                System.out.println("The response was generated from the cache after validating "
                        + "the entry with the origin server");
                break;
        }
    }

  }